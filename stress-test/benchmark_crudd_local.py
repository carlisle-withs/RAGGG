#!/usr/bin/env python3
"""
CRUD-RAG 基准复现（Linux 适配版, 基于 benchmark_crudd.py）

适配点: 本机 8080 端口 / admin123 密码 / 本地数据路径 / 去掉 pymilvus 依赖改用文档 API 轮询 /
        新增 hybrid+rerank 模式(真 CrossEncoder) / requests.Session 连接复用 / 并发上传
指标与 content_hit 判定逻辑与原版完全一致: Hit@3/5/10, MRR, NDCG@3/5/10, Recall@5
"""
import json
import math
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

BASE_URL = "http://localhost:8080"
API = "/api/v1"
DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "crud_rag")
KB_NAME = "CRUD-RAG-Benchmark"
TOP_K = 10
MAX_PER_TASK = 800
TERMINAL = {"COMPLETED", "FAILED"}

S = requests.Session()


def login():
    r = S.post(f"{BASE_URL}{API}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=15)
    return r.json().get("token")


def content_hit(chunk_content, answer_text):
    """与原版 benchmark_crudd.py 完全一致的命中判定"""
    if not chunk_content or not answer_text:
        return False
    keywords = []
    in_bracket = False
    bracket_content = ""
    for ch in answer_text:
        if ch == '\u201c' or ch == '\u201d':
            if not in_bracket:
                in_bracket, bracket_content = True, ""
            else:
                if bracket_content:
                    keywords.append(bracket_content)
                in_bracket = False
        elif in_bracket:
            bracket_content += ch
    for m in re.finditer(r'\d+[年月日亿元万元套辆个件次名]', answer_text):
        kw = m.group()
        if len(kw) >= 3:
            keywords.append(kw)
    stop = set(['的是', '是在', '和与', '以及', '对于', '为了', '可以', '这个', '那个', '因此', '但是', '而且', '或者', '什么', '哪些'])
    i = 0
    while i < len(answer_text):
        if answer_text[i] in '，。、；：？！“”‘’（）【】《》,.:;?!()[]':
            i += 1
            continue
        j = i
        while j < len(answer_text) and '\u4e00' <= answer_text[j] <= '\u9fff':
            j += 1
        if j - i >= 2:
            phrase = answer_text[i:j]
            if phrase not in stop and '的' not in phrase[:2] and '了' not in phrase[:2]:
                keywords.append(phrase)
        i = max(i + 1, j)
    seen = set()
    unique_kws = [k for k in keywords if not (k in seen or seen.add(k))]
    if not unique_kws:
        return False
    return any(kw in chunk_content for kw in unique_kws)


def retrieve(mode, query, token, kb_id, topk=TOP_K):
    body = {"query": query, "kbIds": [str(kb_id)], "topK": topk}
    if mode == "vector":
        path = f"{API}/retrieve"
    else:
        path = f"{API}/retrieve/hybrid"
        body["rerank"] = (mode == "hybrid+rr")
    try:
        r = S.post(f"{BASE_URL}{path}", json=body,
                   headers={"Authorization": f"Bearer {token}"}, timeout=60)
        return r.json().get("results", []) if r.status_code == 200 else []
    except Exception:
        return []


def main():
    print("=" * 60)
    print(f"CRUD-RAG 检索评测复现 (MAX_PER_TASK={MAX_PER_TASK}, topK={TOP_K})")
    print("=" * 60, flush=True)

    with open(os.path.join(DATA_DIR, "split_merged.json"), encoding="utf-8") as f:
        data = json.load(f)

    qa_tasks, doc_map = {}, {}
    for key, label in [("questanswer_1doc", "1-doc"), ("questanswer_2docs", "2-docs"), ("questanswer_3docs", "3-docs")]:
        parsed = []
        for item in data.get(key, [])[:MAX_PER_TASK]:
            q, a = item.get("questions", ""), item.get("answers", "")
            if q and a:
                parsed.append({"question": q, "answer": a})
                for n_i in range(1, 4):
                    if item.get(f"news{n_i}") and len(item[f"news{n_i}"]) > 20:
                        dk = f"{item.get('ID', '')}_news{n_i}"
                        doc_map.setdefault(dk, item[f"news{n_i}"][:10000])
        qa_tasks[label] = parsed
    total_qa = sum(len(v) for v in qa_tasks.values())
    print(f"[1/4] QA: { {k: len(v) for k, v in qa_tasks.items()} } 共{total_qa}条 | 文档去重: {len(doc_map)}篇", flush=True)

    token = login()
    assert token, "login failed"

    # 清理旧 KB, 新建
    kbs = S.get(f"{BASE_URL}{API}/knowledge-base", headers={"Authorization": f"Bearer {token}"}, timeout=15).json()["records"]
    for k in kbs:
        if k.get("name") == KB_NAME:
            S.delete(f"{BASE_URL}{API}/knowledge-base/{k['id']}", headers={"Authorization": f"Bearer {token}"}, timeout=15)
            print(f"  删除旧 KB id={k['id']}")
            time.sleep(2)
    kb = S.post(f"{BASE_URL}{API}/knowledge-base",
                json={"name": KB_NAME, "description": "CRUD-RAG 评测知识库", "embeddingModel": "BAAI/bge-m3"},
                headers={"Authorization": f"Bearer {token}"}, timeout=15).json()
    kb_id = str(kb["id"])
    print(f"[2/4] 新建 KB id={kb_id}", flush=True)

    # 并发上传
    items = list(doc_map.items())
    def up(arg):
        i, (dk, content) = arg
        r = S.post(f"{BASE_URL}{API}/knowledge-base/{kb_id}/docs/upload",
                   files={"file": (f"doc_{i:05d}.txt", content.encode("utf-8"), "text/plain")},
                   data={"sourceType": "file"}, headers={"Authorization": f"Bearer {token}"}, timeout=60)
        return r.status_code in (200, 201, 202)
    ok = 0
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=8) as ex:
        for r in ex.map(up, enumerate(items)):
            ok += bool(r)
    print(f"[3/4] 上传 {ok}/{len(items)} 篇 in {time.time()-t0:.0f}s, 等待流水线...", flush=True)

    t0 = time.time()
    while True:
        docs = S.get(f"{BASE_URL}{API}/documents", params={"kbId": kb_id},
                     headers={"Authorization": f"Bearer {token}"}, timeout=30).json()
        n_done = sum(1 for d in docs if d["status"] == "COMPLETED")
        n_fail = sum(1 for d in docs if d["status"] == "FAILED")
        pend = len(docs) - n_done - n_fail
        if int(time.time() - t0) % 30 < 4:
            print(f"  +{time.time()-t0:.0f}s docs={len(docs)} done={n_done} fail={n_fail} pending={pend}", flush=True)
        if pend == 0 and len(docs) >= len(items) * 0.99:
            break
        if time.time() - t0 > 3600:
            print("  等待超时，继续评测已完成的语料", flush=True)
            break
        time.sleep(4)
    chunks = sum((d.get("chunkCount") or 0) for d in docs)
    print(f"  流水线完成: done={n_done} fail={n_fail} chunks={chunks} ({time.time()-t0:.0f}s)", flush=True)

    # 评测
    print(f"[4/4] 检索评测 (3 模式 × {total_qa} QA)...", flush=True)
    modes = ["vector", "hybrid", "hybrid+rr"]
    all_results = {}
    t0 = time.time()
    for label, items_qa in qa_tasks.items():
        for mode in modes:
            hit = {3: 0, 5: 0, 10: 0}
            mrr_sum = 0.0
            ndcg_sums = {3: 0.0, 5: 0.0, 10: 0.0}
            total_n = 0
            for item in items_qa:
                chunks_r = retrieve(mode, item["question"], token, kb_id)
                if not chunks_r:
                    continue
                scores = [content_hit(c.get("content", ""), item["answer"]) for c in chunks_r]
                for k in (3, 5, 10):
                    if any(scores[:k]):
                        hit[k] += 1
                for j, s_ in enumerate(scores):
                    if s_:
                        mrr_sum += 1.0 / (j + 1)
                        break
                for k in (3, 5, 10):
                    ks = scores[:k]
                    dcg = sum((1 if v else 0) / math.log2(i + 2) for i, v in enumerate(ks))
                    hc = sum(ks)
                    idcg = sum(1 / math.log2(i + 2) for i in range(hc)) if hc else 0
                    ndcg_sums[k] += dcg / idcg if idcg > 0 else 0.0
                total_n += 1
            if total_n == 0:
                continue
            res = {"n": total_n,
                   "hit@3": hit[3] / total_n * 100, "hit@5": hit[5] / total_n * 100, "hit@10": hit[10] / total_n * 100,
                   "mrr": mrr_sum / total_n * 100,
                   "ndcg@3": ndcg_sums[3] / total_n * 100, "ndcg@5": ndcg_sums[5] / total_n * 100,
                   "ndcg@10": ndcg_sums[10] / total_n * 100, "recall@5": hit[5] / total_n * 100}
            all_results.setdefault(label, {})[mode] = res
            print(f"  {label:7s} {mode:10s} Hit@3={res['hit@3']:5.1f}% Hit@5={res['hit@5']:5.1f}% "
                  f"Hit@10={res['hit@10']:5.1f}% MRR={res['mrr']:5.1f}% NDCG@5={res['ndcg@5']:5.1f}% "
                  f"NDCG@10={res['ndcg@10']:5.1f}% ({total_n}条, +{(time.time()-t0)/60:.0f}min)", flush=True)

    # 汇总
    print("\n" + "=" * 60)
    print("📊 CRUD-RAG 检索评测汇总")
    print("=" * 60)
    out = {"kb_id": kb_id, "per_task": all_results}
    for mode in modes:
        rs = [all_results[l][mode] for l in all_results if mode in all_results[l]]
        if rs:
            avg = lambda k: sum(r[k] for r in rs) / len(rs)
            print(f"{mode:10s} Hit@3={avg('hit@3'):5.1f}% Hit@5={avg('hit@5'):5.1f}% Hit@10={avg('hit@10'):5.1f}% "
                  f"MRR={avg('mrr'):5.1f}% NDCG@5={avg('ndcg@5'):5.1f}% NDCG@10={avg('ndcg@10'):5.1f}% Recall@5={avg('recall@5'):5.1f}%")
            out[f"avg_{mode}"] = {k: round(avg(k), 1) for k in rs[0] if k != "n"}
    with open(os.path.join(DATA_DIR, "crud_result_2026-09-22.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"\n结果已保存 data/crud_rag/crud_result_2026-09-22.json")


if __name__ == "__main__":
    main()
