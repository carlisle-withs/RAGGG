#!/usr/bin/env python3
"""
深入诊断 benchmark_fuzzy 的 50% 命中率原因
"""
import json, urllib.request, urllib.error
from difflib import SequenceMatcher

DATA_FILE = r"D:\Workspace\RAGGG\stress-test\data\pubmedqa_golden.jsonl"
BASE_URL = "http://localhost:8081"
KB_ID = "4"
TOPK = 10
MAX_ITEMS = 20
THRESHOLD = 0.60  # 降低阈值测试

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
    print(f"加载 {len(items)} 条, 阈值={THRESHOLD}, 开始诊断...\n")

    hit_count = 0
    fail_items = []

    for idx, item in enumerate(items):
        q = item["question"]
        golden_sents = get_golden(item)
        chunks = retrieve(q, token)

        hits = [best_sim(c.get("content", ""), golden_sents) for c in chunks]
        max_hit = max(hits) if hits else 0.0
        any_hit = any(h >= THRESHOLD for h in hits)
        hit_count += int(any_hit)

        if not any_hit:
            fail_items.append({
                "idx": idx, "question": q,
                "golden_sents": golden_sents,
                "chunks": chunks, "hit_scores": hits
            })

        status = "✅" if any_hit else "❌"
        print(f"{status} [{idx:2d}] {q[:70]}")
        print(f"    golden={len(golden_sents)}, top_sim={max_hit:.3f}")
        if any_hit:
            hit_idxs = [i for i, h in enumerate(hits) if h >= THRESHOLD]
            print(f"    命中: {', '.join(f'#{i}({hits[i]:.3f})' for i in hit_idxs)}")
        else:
            sorted_hits = sorted(enumerate(hits), key=lambda x: -x[1])[:3]
            print(f"    Top3: {', '.join(f'#{i}({s:.3f})' for i, s in sorted_hits)}")
        print()

    print(f"\n{'='*70}")
    print(f"汇总 (阈值={THRESHOLD}): {hit_count}/{len(items)} = {hit_count/len(items)*100:.0f}% 命中")
    print(f"失败 {len(fail_items)} 条")
    if fail_items:
        print(f"\n失败 query ({len(fail_items)} 条):")
        for fi in fail_items:
            print(f"  [{fi['idx']}] {fi['question'][:70]}")
            print(f"       golden={len(fi['golden_sents'])}, top={max(fi['hit_scores']):.3f}")

if __name__ == "__main__":
    main()
