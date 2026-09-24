#!/usr/bin/env python3
"""
PubMedQA 召回效果评测：真实流水线灌入 + 三模式检索对比

阶段:
  ingest - 创建知识库, 上传 196 篇文档走真实 Kafka 流水线, 等待全部 COMPLETED
  eval   - 对每个问题调三种检索模式, 计算 Hit@K / MRR@10 / 延迟
            A vector   : POST /api/v1/retrieve        (rerank=false, 纯 Milvus 向量)
            B hybrid   : POST /api/v1/retrieve/hybrid (rerank=false, Milvus+ES RRF)
            C hybrid+rr: POST /api/v1/retrieve/hybrid (rerank=true,  + SiliconFlow 精排)
状态文件 /tmp/pubmedqa_state.json 记录 kb_id 供两阶段衔接。
"""
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests

BASE = "http://localhost:8080"
API = BASE + "/api/v1"
GOLDEN = "/tmp/pubmedqa_golden.jsonl"
STATE = "/tmp/pubmedqa_state.json"
RESULT = "/tmp/pubmedqa_eval_result.json"
TERMINAL = {"COMPLETED", "FAILED"}


def login():
    r = requests.post(f"{API}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=15)
    r.raise_for_status()
    return r.json()["token"]


def norm(s):
    return " ".join(s.split())


def is_hit(content, goldens):
    c = norm(content)
    for g in goldens:
        if g in c or g[:60] in c or g[-60:] in c:
            return True
    return False


def stage_ingest(token):
    items = [json.loads(l) for l in open(GOLDEN, encoding="utf-8")]
    r = requests.post(f"{API}/knowledge-base", headers={"Authorization": f"Bearer {token}"},
                      json={"name": f"PubMedQA召回评测-{int(time.time())}", "description": "retrieval eval corpus"},
                      timeout=15)
    r.raise_for_status()
    kb_id = int(r.json()["id"])
    print(f"[ingest] kb_id={kb_id}, docs={len(items)}", flush=True)

    doc_ids = []

    def up(i_rec):
        i, it = i_rec
        rr = requests.post(f"{API}/documents/upload",
                           headers={"Authorization": f"Bearer {token}"},
                           files={"file": (f"pubmed-{i:04d}.txt", it["doc_text"].encode(), "text/plain")},
                           data={"kbId": str(kb_id), "chunkStrategy": "fixed"},
                           timeout=60)
        return int(rr.json()["documentId"]) if rr.status_code == 202 else None

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=5) as ex:
        for f in as_completed([ex.submit(up, (i, it)) for i, it in enumerate(items)]):
            did = f.result()
            if did:
                doc_ids.append(did)
    print(f"[ingest] uploaded {len(doc_ids)}/{len(items)} in {time.time()-t0:.1f}s", flush=True)

    # 等待流水线
    t0 = time.time()
    while True:
        rr = requests.get(f"{API}/documents", params={"kbId": kb_id},
                          headers={"Authorization": f"Bearer {token}"}, timeout=15)
        docs = {d["id"]: d["status"] for d in rr.json()}
        n_done = sum(1 for d in doc_ids if docs.get(d) == "COMPLETED")
        n_fail = sum(1 for d in doc_ids if docs.get(d) == "FAILED")
        pend = len(doc_ids) - n_done - n_fail
        if int(time.time() - t0) % 30 < 3:
            print(f"[ingest] +{time.time()-t0:.0f}s done={n_done} failed={n_fail} pending={pend}", flush=True)
        if pend == 0:
            break
        if time.time() - t0 > 1800:
            print("[ingest] TIMEOUT waiting pipeline", flush=True)
            break
        time.sleep(3)
    chunks = sum((d.get("chunkCount") or 0) for d in rr.json())
    print(f"[ingest] FINISHED: done={n_done} failed={n_fail} total_chunks={chunks} wall={time.time()-t0:.0f}s", flush=True)
    Path(STATE).write_text(json.dumps({"kb_id": kb_id, "doc_ids": doc_ids}))
    return kb_id


MODES = {
    "vector":      ("POST", "/retrieve",        False),
    "hybrid":      ("POST", "/retrieve/hybrid", False),
    "hybrid+rr":   ("POST", "/retrieve/hybrid", True),
}


def stage_eval(token, kb_id):
    items = [json.loads(l) for l in open(GOLDEN, encoding="utf-8")]
    res = {m: {"lat": [], "hitk": {3: 0, 5: 0, 10: 0}, "mrr": 0.0, "n": 0} for m in MODES}
    order_diff = 0
    last_orders = {}
    for qi, it in enumerate(items):
        q = it["question"]
        goldens = [norm(g) for g in it["golden_sentences"]]
        for m, (ep, path, rerk) in MODES.items():
            t0 = time.time()
            try:
                rr = requests.post(f"{API}{path}",
                                   headers={"Authorization": f"Bearer {token}"},
                                   json={"query": q, "kbIds": [str(kb_id)], "topK": 10, "rerank": rerk},
                                   timeout=60)
                lat = time.time() - t0
                results = rr.json().get("results") or []
            except Exception as e:
                print(f"[eval] q{qi} mode {m} error {e}", flush=True)
                continue
            res[m]["n"] += 1
            res[m]["lat"].append(lat)
            flags = [is_hit(x.get("content", ""), goldens) for x in results]
            for k in (3, 5, 10):
                if any(flags[:k]):
                    res[m]["hitk"][k] += 1
            for rank, f_ in enumerate(flags, 1):
                if f_:
                    res[m]["mrr"] += 1.0 / rank
                    break
            if m in ("hybrid", "hybrid+rr"):
                last_orders[m] = [x.get("chunkId") for x in results]
        if last_orders.get("hybrid") != last_orders.get("hybrid+rr"):
            order_diff += 1
        if (qi + 1) % 25 == 0:
            print(f"[eval] {qi+1}/{len(items)} queries done", flush=True)

    n = len(items)
    print("\n===== PubMedQA 召回评测结果 =====", flush=True)
    print(f"queries={n}  kb_id={kb_id}  (rerank 实际改变排序的查询数: {order_diff}/{n})", flush=True)
    hdr = f"{'mode':<12}{'Hit@3':>8}{'Hit@5':>8}{'Hit@10':>8}{'MRR@10':>9}{'lat_mean':>10}{'lat_p95':>9}"
    print(hdr, flush=True)
    out = {}
    for m in MODES:
        r = res[m]
        lat = sorted(r["lat"]) or [0]
        h3, h5, h10 = (r["hitk"][3] / n * 100, r["hitk"][5] / n * 100, r["hitk"][10] / n * 100)
        mrr = r["mrr"] / n
        print(f"{m:<12}{h3:>7.1f}%{h5:>7.1f}%{h10:>7.1f}%{mrr:>9.3f}"
              f"{sum(lat)/len(lat):>9.3f}s{lat[int(len(lat)*.95)]:>8.3f}s", flush=True)
        out[m] = {"hit@3": round(h3, 1), "hit@5": round(h5, 1), "hit@10": round(h10, 1),
                  "mrr@10": round(mrr, 4), "latency_mean_s": round(sum(lat)/len(lat), 3),
                  "latency_p95_s": round(lat[int(len(lat)*.95)], 3), "n": r["n"]}
    Path(RESULT).write_text(json.dumps({"kb_id": kb_id, "queries": n, "rerank_reordered_queries": order_diff,
                                        "modes": out}, ensure_ascii=False, indent=2))
    print(f"[eval] saved {RESULT}", flush=True)


if __name__ == "__main__":
    stage = sys.argv[1] if len(sys.argv) > 1 else "all"
    token = login()
    if stage in ("ingest", "all"):
        kb_id = stage_ingest(token)
    else:
        kb_id = json.loads(Path(STATE).read_text())["kb_id"]
    if stage in ("eval", "all"):
        stage_eval(token, kb_id)
