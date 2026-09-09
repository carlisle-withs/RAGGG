#!/usr/bin/env python3
"""
重新 ingest PubMedQA 测试（用新分块参数 128 tokens）

流程：
  1. 清空 Milvus kb_id=4 的旧 chunks
  2. 上传 PubMedQA 文档（走系统 API）
  3. 等待 chunk + index 完成
  4. 评估 Hit@K
"""
import json, time, requests, aiohttp, asyncio
from difflib import SequenceMatcher

BASE_URL = "http://localhost:8081"
SF_API_KEY = "***REMOVED***"
KB_NAME = "PubMedQA全链路测试"
MAX_ITEMS = 10
POLL_INTERVAL = 3
MAX_WAIT = 300
TOP_K = 10

# ─────────────────────────────────────────────────────────────────────────────
# Step 0: 加载 PubMedQA
# ─────────────────────────────────────────────────────────────────────────────
print("=" * 60)
print("Step 0: 加载 PubMedQA 测试数据")
print("=" * 60)

# 从本地 JSONL 加载（避免 HuggingFace 网络超时）
LOCAL_FILE = "D:\\Workspace\\RAGGG\\stress-test\\data\\pubmedqa_golden.jsonl"
with open(LOCAL_FILE, encoding="utf-8") as f:
    items = [json.loads(line) for line in f][:MAX_ITEMS]
raw0 = items[0].get("raw_item", items[0])
print(f"  加载了 {len(items)} 条 PubMedQA，每条含 {len(raw0.get('documents', []))} 篇文档")

def get_golden_sentences(item: dict) -> list[str]:
    raw = item.get("raw_item", item)  # 兼容本地文件格式
    sentences = []
    for doc_idx, doc_sents in enumerate(raw.get("documents_sentences", [])):
        for sent_id, sent_text in doc_sents:
            if sent_id in raw.get("all_relevant_sentence_keys", []):
                sentences.append(sent_text.strip())
    return sentences

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: 找到知识库
# ─────────────────────────────────────────────────────────────────────────────
print("\nStep 1: 确认测试知识库")
print("=" * 60)

resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == KB_NAME), None)

if not kb:
    resp = requests.post(
        f"{BASE_URL}/api/v1/knowledge-base",
        json={"name": KB_NAME, "description": "PubMedQA 全链路测试", "embeddingModel": "BAAI/bge-m3", "chunkStrategy": "intelligent"}
    )
    kb_id = resp.json()["id"]
    print(f"  知识库创建成功: id={kb_id}")
else:
    kb_id = kb["id"]
    print(f"  知识库已存在: id={kb_id}")

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: 清空 Milvus 里 kb_id=4 的旧 chunks
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 2: 清空 Milvus kb_id={kb_id} 的旧 chunks")
print("=" * 60)

from pymilvus import connections, Collection

connections.connect(host="localhost", port="29530", alias="default")
c = Collection("rag_chunks")
c.load()

old_chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
print(f"  当前 kb_id={kb_id} 的 chunk 数: {len(old_chunks)}")

if old_chunks:
    for chunk in old_chunks:
        try:
            c.delete(expr=f'chunk_id == "{chunk["chunk_id"]}"')
        except Exception as e:
            print(f"  删除 chunk {chunk['chunk_id']} 失败: {e}")
    print(f"  已删除 {len(old_chunks)} 个旧 chunk")
else:
    print("  无旧 chunk，跳过清理")

c.flush()
c.release()
c.load()
print(f"  Milvus 清理完成")

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: 上传文档
# ─────────────────────────────────────────────────────────────────────────────
print("\nStep 3: 上传 PubMedQA 文档")
print("=" * 60)

def extract_doc_title(question: str, doc_idx: int, doc_text: str) -> str:
    first_line = doc_text.split("\n")[0].strip()
    if len(first_line) > 60:
        first_line = first_line[:60] + "..."
    return f"[{question[:40]}...] doc-{doc_idx}: {first_line}"

uploaded = 0
skipped = 0

for item_idx, item in enumerate(items):
    raw = item.get("raw_item", item)
    question = item["question"]
    docs = raw.get("documents", [])
    for doc_idx, doc_text in enumerate(docs):
        title = extract_doc_title(question, doc_idx, doc_text)
        content = doc_text.strip()
        files = {"file": (f"pubmedqa_{item_idx}_{doc_idx}.txt", content.encode("utf-8"), "text/plain")}
        data = {"sourceType": "file"}
        try:
            resp = requests.post(
                f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs/upload",
                files=files, data=data, timeout=60
            )
            if resp.status_code in (200, 201, 202):
                uploaded += 1
            else:
                print(f"  上传失败 [{item_idx}_{doc_idx}]: {resp.status_code} {resp.text[:100]}")
                skipped += 1
        except Exception as e:
            print(f"  上传异常 [{item_idx}_{doc_idx}]: {e}")
            skipped += 1

    if (item_idx + 1) % 5 == 0:
        print(f"  进度: {item_idx+1}/{len(items)} 条 PubMedQA，已上传 {uploaded} 篇文档")

print(f"\n  上传完成: {uploaded} 篇文档上传，{skipped} 篇跳过")

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: 等待处理完成
# ─────────────────────────────────────────────────────────────────────────────
print("\nStep 4: 等待文档处理完成")
print("=" * 60)

async def wait_for_processing():
    start = time.time()
    while time.time() - start < MAX_WAIT:
        elapsed = int(time.time() - start)
        resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs", params={"current": 1, "size": 1})
        doc_count = resp.json().get("total", 0) if resp.status_code == 200 else 0

        from pymilvus import connections, Collection
        connections.connect(host="localhost", port="29530", alias="default")
        c = Collection("rag_chunks")
        c.load()
        results = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
        chunk_count = len(results)

        print(f"  [{elapsed}s] 文档: {doc_count}, Milvus chunks: {chunk_count}")

        if doc_count >= uploaded and chunk_count >= uploaded * 2:
            print("  处理完成！")
            return True

        await asyncio.sleep(POLL_INTERVAL)

    print(f"  等待超时，继续下一步")
    return False

asyncio.run(wait_for_processing())

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: 检索质量评估
# ─────────────────────────────────────────────────────────────────────────────
print("\nStep 5: 检索质量评估")
print("=" * 60)

def content_match_ratio(chunk_text: str, sentence_text: str) -> float:
    chunk_lower = chunk_text.lower()
    sent_lower = sentence_text.lower()
    if sent_lower in chunk_lower:
        return 1.0
    return SequenceMatcher(None, sent_lower, chunk_lower).ratio()

def chunk_matches_golden(chunk_text: str, golden_sentences: list[str], threshold: float = 0.75) -> bool:
    for gs in golden_sentences:
        if len(gs) < 20:
            continue
        if content_match_ratio(chunk_text, gs) >= threshold:
            return True
    return False

async def eval_question(question: str, golden_sentences: list[str]) -> dict:
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

    ranked_hits = [chunk_matches_golden(r.get("content", ""), golden_sentences) for r in results]
    hit_at_k = {k: int(any(ranked_hits[:k])) for k in [3, 5, 10]}

    rr = 0.0
    for i, hit in enumerate(ranked_hits):
        if hit:
            rr = 1.0 / (i + 1)
            break

    def dcg(k):
        return sum((1 if ranked_hits[i] else 0) / (i + 2) ** 0.5 for i in range(k))

    ideal = sum(1 / (i + 2) ** 0.5 for i in range(sum(1 for _ in golden_sentences)))
    ndcg = {k: dcg(k) / ideal if ideal > 0 else 0 for k in [3, 5, 10]}

    return {"question": question[:50], "hit_at_k": hit_at_k, "rr": rr, "ndcg": ndcg, "results_count": len(results)}

async def main_eval():
    tasks = [eval_question(item["question"], get_golden_sentences(item)) for item in items]
    all_results = []
    for i, t in enumerate(asyncio.as_completed(tasks)):
        r = await t
        if r:
            all_results.append(r)
        if (i + 1) % 5 == 0:
            print(f"  评估进度: {i+1}/{len(items)}")

    print("\n" + "=" * 60)
    print(f"全链路检索质量评估结果（新分块 128 tokens + 25 overlap）")
    print(f"   知识库: {KB_NAME} (kb_id={kb_id})")
    print(f"   分块: intelligent chunker (maxTokensPerChunk=128, overlapTokens=25)")
    print(f"   向量: SiliconFlow BAAI/bge-m3 (1024维)")
    print(f"   检索: /api/v1/retrieve")
    print(f"   判断: 内容匹配 golden sentences (SequenceMatcher0.75)")
    print(f"   查询数: {len(all_results)}")
    print()
    print(f"   {'K':>5}   {'Hit@K':>8}   {'MRR':>8}   {'NDCG@K':>8}")
    print(f"   {'-'*5}   {'-'*8}   {'-'*8}   {'-'*8}")

    for k in [3, 5, 10]:
        hit_rate = sum(r["hit_at_k"].get(k, 0) for r in all_results) / len(all_results) * 100
        mrr = sum(r["rr"] for r in all_results) / len(all_results) * 100
        ndcg = sum(r["ndcg"].get(k, 0) for r in all_results) / len(all_results) * 100
        print(f"   @{k:<4}   {hit_rate:>7.1f}%   {mrr:>7.1f}%   {ndcg:>7.1f}%")

    print()
    overall_hit = sum(r["hit_at_k"].get(3, 0) for r in all_results) / len(all_results) * 100
    rating = "优秀" if overall_hit > 80 else "一般" if overall_hit > 50 else "较差"
    print(f"   评级: {rating}")
    print("=" * 60)

asyncio.run(main_eval())
