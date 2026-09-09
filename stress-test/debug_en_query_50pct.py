#!/usr/bin/env python3
"""精简诊断：只跑第一条 item，不打印全文，只输出汇总和相似度表"""
import json, time, urllib.request, urllib.error
from difflib import SequenceMatcher

DATA_FILE = r"D:\Workspace\RAGGG\stress-test\data\pubmedqa_golden.jsonl"
BASE_URL = "http://localhost:8081"
KB_ID = "4"
TOPK = 10

def load_items():
    items = []
    with open(DATA_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items

def login():
    req = urllib.request.Request(
        f"{BASE_URL}/api/v1/auth/login",
        data=json.dumps({"username": "admin", "password": "admin"}).encode(),
        headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read())
            return data.get("data", {}).get("token") or data.get("token")
    except:
        return None

def retrieve(query, token):
    body = json.dumps({"query": query, "kbIds": [KB_ID], "topK": TOPK}).encode()
    req = urllib.request.Request(
        f"{BASE_URL}/api/v1/retrieve", data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST"
    )
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            latency = (time.time() - start) * 1000
            data = json.loads(resp.read())
            return data.get("results", []), latency
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode()[:200]}")
        return [], 0
    except Exception as e:
        print(f"错误: {e}")
        return [], 0

def best_sim(chunk_content, candidates):
    if not candidates:
        return 0.0
    return max(SequenceMatcher(None, chunk_content, t).ratio() for t in candidates)

def diagnose(item, idx, token):
    q = item["question"]
    golden_chunks = item.get("chunks", [])
    golden_ids = set(item.get("relevant_chunk_ids", []))
    golden_by_id = {c["id"]: c["text"] for c in golden_chunks}
    rel_texts = [golden_by_id[i] for i in golden_ids if i in golden_by_id]
    all_texts = [c["text"] for c in golden_chunks if c.get("text")]

    chunks, latency = retrieve(q, token)

    print(f"\n{'='*60}")
    print(f"Item#{idx}: {q[:80]}")
    print(f"  ret_chunks={len(chunks)}  latency={latency:.0f}ms")
    print(f"  golden_chunks={len(golden_chunks)}  relevant={len(rel_texts)}")
    print(f"  relevant_ids: {golden_ids}")
    print()

    if not chunks:
        print("  ❌ 返回 0 条")
        return None

    print(f"  {'Retrieved(前50)':<52} {'sim_rel':<10} {'sim_all':<10} {'命中?':<6}")
    print("  " + "-"*80)
    for i, c in enumerate(chunks):
        content = c.get("content", "")[:50]
        s_rel = best_sim(c.get("content", ""), rel_texts)
        s_all = best_sim(c.get("content", ""), all_texts)
        hit = "✅" if s_rel >= 0.75 else ("⚠️" if s_all >= 0.75 else "  ")
        print(f"  {content:<52} {s_rel:.3f}     {s_all:.3f}     {hit}")

    hits_rel = sum(1 for c in chunks if best_sim(c.get("content",""), rel_texts) >= 0.75)
    hits_all = sum(1 for c in chunks if best_sim(c.get("content",""), all_texts) >= 0.75)
    print()
    print(f"  命中 relevant: {hits_rel}/{len(chunks)}")
    print(f"  命中任意 GS:   {hits_all}/{len(chunks)}")
    if len(chunks) < 5:
        print("  ⚠️  chunk 数量极少，数据不完整")
    if hits_rel == 0 and hits_all == 0:
        print("  ❌ 未命中：检索内容与 golden sentences 相似度均 < 0.75")
    elif hits_rel > 0:
        print("  ✅ 检索正常")
    return {"hit": hits_rel > 0, "n_chunks": len(chunks), "n_hits_rel": hits_rel,
            "n_hits_all": hits_all, "n_rel": len(rel_texts), "n_all": len(all_texts)}

def main():
    print("🔍 RAG 检索诊断")
    items = load_items()
    print(f"加载 {len(items)} 条数据\n")
    token = login()
    if not token:
        print("⚠️  无 token")
    results = []
    for i in range(min(5, len(items))):
        try:
            r = diagnose(items[i], i, token)
            if r is not None:
                results.append(r)
        except Exception as e:
            print(f"Item {i} 失败: {e}")
    print(f"\n{'='*60}")
    print("汇总:")
    for j, r in enumerate(results):
        s = "✅" if r["hit"] else "❌"
        print(f"  {s} Item#{j}: ret={r['n_chunks']} hit_rel={r['n_hits_rel']} hit_all={r['n_hits_all']} "
              f"golden_rel={r['n_rel']} golden_all={r['n_all']}")
    n_small = sum(1 for r in results if r["n_chunks"] < 5)
    n_zero = sum(1 for r in results if r["n_hits_rel"] == 0)
    print()
    if n_small > 0:
        print(f"💡 {n_small} 条只返回 <5 chunks → 数据不完整（kb_id=4 缺数据）")
    elif n_zero > 0:
        print(f"💡 {n_zero} 条 relevant 未命中 → 检索质量问题")

if __name__ == "__main__":
    main()
