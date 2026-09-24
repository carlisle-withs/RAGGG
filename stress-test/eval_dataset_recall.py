#!/usr/bin/env python3
"""
通用数据集召回评测: 共享语料 + 独立查询标注
用法: python3 eval_dataset_recall.py --docs scifact_docs.jsonl --queries scifact_queries.jsonl --name SciFact
流程: 建KB -> 并发上传语料 -> 等待流水线 -> 3模式评测(vector/hybrid/hybrid+rerank)
"""
import argparse
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor

import requests

BASE = "http://localhost:8080"
API = "/api/v1"
TERMINAL = {"COMPLETED", "FAILED"}
S = requests.Session()


def norm(s):
    return " ".join((s or "").split())


def is_hit(content, goldens):
    c = norm(content)
    for g in goldens:
        if g in c or g[:60] in c or g[-60:] in c:
            return True
    return False


def retrieve(mode, query, token, kb_id, topk=10):
    body = {"query": query, "kbIds": [str(kb_id)], "topK": topk}
    if mode == "vector":
        path = f"{API}/retrieve"
    else:
        path = f"{API}/retrieve/hybrid"
        body["rerank"] = (mode == "hybrid+rr")
    try:
        r = S.post(f"{BASE}{path}", json=body, headers={"Authorization": f"Bearer {token}"}, timeout=60)
        return r.json().get("results", []) if r.status_code == 200 else []
    except Exception:
        return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--docs", required=True)
    ap.add_argument("--queries", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--max-docs", type=int, default=0)
    ap.add_argument("--kb-id", default="", help="复用已有知识库，跳过灌库")
    args = ap.parse_args()
    DD = os.path.dirname(os.path.abspath(args.docs))

    docs = [json.loads(l) for l in open(args.docs, encoding="utf-8")]
    if args.max_docs:
        docs = docs[:args.max_docs]
    queries = [json.loads(l) for l in open(args.queries, encoding="utf-8")]
    print(f"[{args.name}] docs={len(docs)} queries={len(queries)}", flush=True)

    token = S.post(f"{BASE}{API}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=15).json()["token"]

    if args.kb_id:
        kb_id = args.kb_id
        dl = S.get(f"{BASE}{API}/documents", params={"kbId": kb_id},
                   headers={"Authorization": f"Bearer {token}"}, timeout=30).json()
        nd = sum(1 for d in dl if d["status"] == "COMPLETED")
        chunks = sum((d.get("chunkCount") or 0) for d in dl)
        print(f"[{args.name}] reuse kb_id={kb_id}: docs={nd}, chunks={chunks}", flush=True)
    else:
        kb = S.post(f"{BASE}{API}/knowledge-base", json={"name": f"{args.name}-eval-{int(time.time())}"},
                    headers={"Authorization": f"Bearer {token}"}, timeout=15).json()
        kb_id = kb["id"]
        print(f"[{args.name}] kb_id={kb_id}", flush=True)

        def up(i_d):
            i, d = i_d
            r = S.post(f"{BASE}{API}/documents/upload",
                       files={"file": (d["fname"], d["text"].encode("utf-8"), "text/plain")},
                       data={"kbId": str(kb_id), "chunkStrategy": "fixed"},
                       headers={"Authorization": f"Bearer {token}"}, timeout=90)
            return r.status_code == 202
        ok = 0
        with ThreadPoolExecutor(max_workers=8) as ex:
            for r in ex.map(up, enumerate(docs)):
                ok += bool(r)
        print(f"[{args.name}] uploaded {ok}/{len(docs)}", flush=True)

        t0 = time.time()
        while True:
            dl = S.get(f"{BASE}{API}/documents", params={"kbId": kb_id},
                       headers={"Authorization": f"Bearer {token}"}, timeout=30).json()
            nd = sum(1 for d in dl if d["status"] == "COMPLETED")
            nf = sum(1 for d in dl if d["status"] == "FAILED")
            pend = len(dl) - nd - nf
            if int(time.time() - t0) % 30 < 3:
                print(f"[{args.name}] +{time.time()-t0:.0f}s done={nd} fail={nf} pending={pend}", flush=True)
            if pend == 0 and len(dl) >= len(docs) * 0.99:
                break
            if time.time() - t0 > 3600:
                print(f"[{args.name}] ingest TIMEOUT", flush=True)
                break
            time.sleep(4)
        chunks = sum((d.get("chunkCount") or 0) for d in dl)
        nf = sum(1 for d in dl if d["status"] == "FAILED")
        print(f"[{args.name}] pipeline done: {nd}/{len(docs)}, chunks={chunks}, fail={nf} ({time.time()-t0:.0f}s)", flush=True)

    modes = ["vector", "hybrid", "hybrid+rr"]
    out = {"name": args.name, "kb_id": kb_id, "docs": len(docs), "chunks": chunks, "queries": len(queries)}
    t0 = time.time()
    for mode in modes:
        hit = {3: 0, 5: 0, 10: 0}
        mrr = 0.0
        n = 0
        lat = []
        for q in queries:
            t1 = time.time()
            res = retrieve(mode, q["question"], token, kb_id)
            lat.append(time.time() - t1)
            if not res:
                continue
            flags = [is_hit(x.get("content", ""), q["golden"]) for x in res]
            for k in (3, 5, 10):
                if any(flags[:k]):
                    hit[k] += 1
            for j, f_ in enumerate(flags, 1):
                if f_:
                    mrr += 1.0 / j
                    break
            n += 1
        lat.sort()
        res = {"n": n,
               "hit@3": round(hit[3] / n * 100, 1), "hit@5": round(hit[5] / n * 100, 1),
               "hit@10": round(hit[10] / n * 100, 1), "mrr@10": round(mrr / n * 100, 1),
               "lat_mean": round(sum(lat) / len(lat), 3), "lat_p95": round(lat[int(len(lat) * .95)], 3)}
        out[mode] = res
        print(f"[{args.name}] {mode:10s} Hit@3={res['hit@3']:5.1f}% Hit@5={res['hit@5']:5.1f}% "
              f"Hit@10={res['hit@10']:5.1f}% MRR={res['mrr@10']:5.1f}% lat={res['lat_mean']}s "
              f"(+{(time.time()-t0)/60:.0f}min)", flush=True)
    with open(os.path.join(DD, f"{args.name.lower()}_eval_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"[{args.name}] DONE, saved", flush=True)


if __name__ == "__main__":
    main()
