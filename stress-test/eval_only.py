"""直接跑评测，跳过上传（文档已入库）"""
import json, requests, aiohttp, asyncio
from pathlib import Path
from datasets import load_dataset
from difflib import SequenceMatcher

BASE_URL = "http://localhost:8081"
SF_API_KEY = "***REMOVED***"
KB_NAME = "PubMedQA全链路测试"
MAX_ITEMS = 10
TOP_K = 10

# 找知识库
resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == KB_NAME), None)
if not kb:
    print("找不到知识库")
    exit(1)
kb_id = kb["id"]
print(f"知识库: {KB_NAME} (kb_id={kb_id})")

# 加载 PubMedQA
ds = load_dataset("rungalileo/ragbench", "pubmedqa", split="test", streaming=True)
items = []
for i, item in enumerate(ds):
    items.append(item)
    if i >= MAX_ITEMS - 1:
        break
print(f"加载 {len(items)} 条 PubMedQA")

def get_golden_sentences(item):
    sentences = []
    for doc_sents in item["documents_sentences"]:
        for sent_id, sent_text in doc_sents:
            if sent_id in item["all_relevant_sentence_keys"]:
                sentences.append(sent_text.strip())
    return sentences

def chunk_matches_golden(chunk_text, golden_sents, threshold=0.75):
    for gs in golden_sents:
        if len(gs) < 20:
            continue
        gs_lower = gs.lower()
        chunk_lower = chunk_text.lower()
        if gs_lower in chunk_lower:
            return True
        ratio = SequenceMatcher(None, gs_lower, chunk_lower).ratio()
        if ratio >= threshold:
            return True
    return False

async def eval_one(question, golden_sents):
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{BASE_URL}/api/v1/retrieve",
                json={"query": question, "kbIds": [kb_id], "topK": TOP_K},
                timeout=aiohttp.ClientTimeout(total=30)
            ) as resp:
                if resp.status != 200:
                    return None
                data = await resp.json()
                results = data.get("results", [])
    except Exception as e:
        print(f"  检索错误: {e}")
        return None

    ranked_hits = [chunk_matches_golden(r.get("content", ""), golden_sents) for r in results]
    hit_at_k = {k: int(any(ranked_hits[:k])) for k in [3, 5, 10]}

    rr = 0.0
    for i, hit in enumerate(ranked_hits):
        if hit:
            rr = 1.0 / (i + 1)
            break

    def dcg(k):
        return sum((1 if ranked_hits[i] else 0) / (i + 2) ** 0.5 for i in range(k))

    ideal = sum(1 / (i + 2) ** 0.5 for i in range(sum(1 for _ in golden_sents)))
    ndcg = {k: dcg(k) / ideal if ideal > 0 else 0 for k in [3, 5, 10]}

    return {"question": question[:50], "hit_at_k": hit_at_k, "rr": rr, "ndcg": ndcg}

async def main():
    tasks = [eval_one(item["question"], get_golden_sentences(item)) for item in items]
    results = []
    for i, t in enumerate(asyncio.as_completed(tasks)):
        r = await t
        if r:
            results.append(r)
        print(f"  进度: {i+1}/{len(items)}, 当前累计: {len(results)}")

    print("\n" + "=" * 60)
    print(f"📊 全链路检索质量评估结果（走你们系统）")
    print(f"   知识库: {KB_NAME} (kb_id={kb_id})")
    print(f"   分块: 你们的 intelligent chunker")
    print(f"   向量: SiliconFlow BAAI/bge-m3 (1024维)")
    print(f"   检索: 你们的 RetrievalApplicationService")
    print(f"   判断: 内容匹配 golden sentences")
    print(f"   查询数: {len(results)}")
    print()
    print(f"   {'K':>5}   {'Hit@K':>8}   {'MRR':>8}   {'NDCG@K':>8}")
    print(f"   {'-'*5}   {'-'*8}   {'-'*8}   {'-'*8}")

    for k in [3, 5, 10]:
        hit_rate = sum(r["hit_at_k"].get(k, 0) for r in results) / len(results) * 100
        mrr = sum(r["rr"] for r in results) / len(results) * 100
        ndcg = sum(r["ndcg"].get(k, 0) for r in results) / len(results) * 100
        print(f"   @{k:<4}   {hit_rate:>7.1f}%   {mrr:>7.1f}%   {ndcg:>7.1f}%")

    print()
    if any(sum(r["hit_at_k"].get(k, 0) for r in results) / len(results) > 0.6 for k in [3, 5, 10]):
        rating = "🟢 良好"
    elif any(sum(r["hit_at_k"].get(k, 0) for r in results) / len(results) > 0.3 for k in [3, 5, 10]):
        rating = "🟡 一般"
    else:
        rating = "🔴 较差"
    print(f"   评级: {rating}")
    print("=" * 60)

asyncio.run(main())
