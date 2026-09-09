#!/usr/bin/env python3
"""
测试不同相似度阈值对 Hit@K 的影响
"""
import json, urllib.request, urllib.error
from difflib import SequenceMatcher

DATA_FILE = r"D:\Workspace\RAGGG\stress-test\data\pubmedqa_golden.jsonl"
BASE_URL = "http://localhost:8081"
KB_ID = "4"
TOPK = 10
MAX_ITEMS = 20

def load_items():
    with open(DATA_FILE, encoding="utf-8") as f:
        return [json.loads(line) for line in f][:MAX_ITEMS]

def login():
    req = urllib.request.Request(
        f"{BASE_URL}/api/v1/auth/login",
        data=json.dumps({"username": "admin", "password": "admin"}).encode(),
        headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read()).get("data", {}).get("token")
    except:
        return None

def retrieve(query, token):
    body = json.dumps({"query": query, "kbIds": [KB_ID], "topK": TOPK}).encode()
    req = urllib.request.Request(
        f"{BASE_URL}/api/v1/retrieve", data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read()).get("results", [])
    except:
        return []

def get_golden(item):
    raw = item.get("raw_item", item)
    sents = []
    for di, ds in enumerate(raw.get("documents_sentences", [])):
        for sid, st in ds:
            if sid in raw.get("all_relevant_sentence_keys", []):
                sents.append(st.strip())
    return sents

def best_sim(chunk_text, golden_sents):
    if not golden_sents:
        return 0.0
    return max(SequenceMatcher(None, chunk_text, g).ratio() for g in golden_sents)

def main():
    items = load_items()
    token = login() or ""
    print(f"加载 {len(items)} 条, 获取检索结果...\n")

    # 预取所有 query 的检索结果
    all_chunks = []
    all_golden = []
    for item in items:
        q = item["question"]
        gs = get_golden(item)
        chunks = retrieve(q, token)
        hits = [best_sim(c.get("content", ""), gs) for c in chunks]
        all_chunks.append(hits)
        all_golden.append(gs)
        print(f"  [{items.index(item):2d}] {q[:50]}... golden={len(gs)}")

    print(f"\n{'='*60}")
    print("Hit@K 随阈值变化曲线")
    print(f"{'='*60}")
    print(f"{'阈值':<8} {'Hit@3':<10} {'Hit@5':<10} {'Hit@10':<10} {'MRR':<10}")
    print(f"{'-'*8} {'-'*10} {'-'*10} {'-'*10} {'-'*10}")

    for threshold in [0.30, 0.40, 0.50, 0.60, 0.65, 0.70, 0.75, 0.80]:
        hit_k = {3: 0, 5: 0, 10: 0}
        mrr_sum = 0.0

        for i in range(len(items)):
            hits = all_chunks[i]
            ranked_hits = [h >= threshold for h in hits]

            for k in [3, 5, 10]:
                hit_k[k] += int(any(ranked_hits[:k]))

            rr = 0.0
            for j, hit in enumerate(ranked_hits):
                if hit:
                    rr = 1.0 / (j + 1)
                    break
            mrr_sum += rr

        n = len(items)
        print(f"  {threshold:<6.2f}  {hit_k[3]/n*100:>7.1f}%   {hit_k[5]/n*100:>7.1f}%   {hit_k[10]/n*100:>7.1f}%   {mrr_sum/n*100:>7.1f}%")

    # 分析：哪些 query 在阈值 0.75 时失败，阈值 0.60 时成功
    print(f"\n{'='*60}")
    print("从失败→成功的 query（阈值 0.75→0.60）")
    print(f"{'='*60}")
    for i in range(len(items)):
        hits = all_chunks[i]
        gs = all_golden[i]
        was_hit = any(h >= 0.75 for h in hits)
        now_hit = any(h >= 0.60 for h in hits)
        if not was_hit and now_hit:
            print(f"  [{i:2d}] {items[i]['question'][:70]}")
            print(f"       golden={len(gs)}, top_sim={max(hits):.3f}")
            top3 = sorted(enumerate(hits), key=lambda x: -x[1])[:3]
            print(f"       Top3: {', '.join(f'#{j}({s:.3f})' for j, s in top3)}")

if __name__ == "__main__":
    main()
