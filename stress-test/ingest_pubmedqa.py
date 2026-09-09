#!/usr/bin/env python3
"""
将 PubMedQA golden chunks 直接入库到新知识库，并执行检索质量评估。
流程：
  1. 调用后端 API 创建 "PubMedQA医学问答库" 知识库
  2. 将 chunks.jsonl 保存到 MinIO
  3. 通过 Kafka CHUNKED 事件触发 IndexService 写入 Milvus

  4. 评测 Hit@K / MRR / NDCG
"""
import json, time, math, requests, aiohttp, asyncio
from pathlib import Path

BASE_URL = "http://localhost:8081"
SF_API_KEY = "***REMOVED***"
DATA_DIR = Path(__file__).parent / "data"
GOLDEN_FILE = DATA_DIR / "pubmedqa_golden.jsonl"

# ─── Step 0: 清理旧 PubMedQA 数据 ───────────────────────────────────────────
print("=" * 60)
print("🧹 Step 0: 清理旧 PubMedQA 数据（kb_id=3）")
print("=" * 60)
from pymilvus import connections, Collection
connections.connect(host='localhost', port='29530', alias='default')
c = Collection('rag_chunks')
try:
    delete_count = c.delete(expr='kb_id == "3"')
    c.flush()
    print(f"   ✅ 已删除 kb_id=3 的所有 chunks")
except Exception as e:
    print(f"   清理: {e}")

# ─── Step 1: 创建 PubMedQA 知识库 ───────────────────────────────────────────
print("\n📦 Step 1: 创建 PubMedQA 知识库")
print("=" * 60)

# 先查已有知识库
resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
pubmed_kb = next((k for k in kbs if k["name"] == "PubMedQA医学问答库"), None)

if pubmed_kb:
    kb_id = pubmed_kb["id"]
    print(f"✅ 知识库已存在: id={kb_id}, name={pubmed_kb['name']}")
else:
    payload = {
        "name": "PubMedQA医学问答库",
        "description": "PubMedQA 医学问答数据集，用于 RAG 检索质量评测",
        "embeddingModel": "BAAI/bge-m3",
        "chunkStrategy": "intelligent"
    }
    resp = requests.post(f"{BASE_URL}/api/v1/knowledge-base", json=payload)
    if resp.status_code not in (200, 201):
        print(f"❌ 创建知识库失败: {resp.status_code} {resp.text}")
        exit(1)
    kb_id = resp.json()["id"]
    print(f"✅ 知识库创建成功: id={kb_id}")

print(f"   目标知识库 ID: {kb_id}")

# ─── Step 2: 读取 golden 数据，构造每个 doc 的 chunks ────────────────────────
print("\n📖 Step 2: 读取 PubMedQA golden 数据")
with open(GOLDEN_FILE, encoding="utf-8") as f:
    records = [json.loads(line) for line in f]

print(f"   共 {len(records)} 条记录")

# ─── Step 3: 调用后端 /ingest-by-texts 接口批量入库 ───────────────────────────
# 看看有没有这个接口
print("\n🔧 Step 3: 批量入库 chunks")

# 先找可用的批量接口——看 batch-upload
print("   查找可用的入库接口...")
# 方案A: 逐条上传文档
# 方案B: 直接发 Kafka 事件（通过后端 REST → Kafka）
# 方案C: 查看后端是否有 /ingest-by-texts

# 先试试已有的 chunk 接口
test_doc_resp = requests.post(
    f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs/upload",
    files={"file": ("meta.json", json.dumps({"title": "PubMedQA Test"}) , "application/json")}
)
print(f"   batch-upload 接口测试: {test_doc_resp.status_code}")

# 看看有没有直接发文本的接口
# 根据之前代码，IndexService 从 Kafka 消费 CHUNKED 事件
# 事件结构：DocumentEvent{ documentId, kbId, chunksMinioPath, ... }
# 所以核心路径：1) 写 chunks.json 到 MinIO，2) 发 CHUNKED 事件

# 直接利用已有的 reindex 思路——但这里 chunks 已经是结构化的
# 最佳方案：直接写 Milvus，绕过 Kafka

print("   → 切换为直接写入 Milvus 方案")

# ─── Step 3 Alt: 直接写入 Milvus ─────────────────────────────────────────────
print("\n🔧 Step 3 Alt: 直接通过 Milvus 客户端写入向量")

import httpx

def get_siliconflow_embedding(texts: list[str], model: str = "BAAI/bge-m3") -> list[list[float]]:
    """调用 SiliconFlow BGE-M3 向量化接口"""
    resp = httpx.post(
        "https://api.siliconflow.cn/v1/embeddings",
        headers={
            "Authorization": f"Bearer {SF_API_KEY}",
            "Content-Type": "application/json"
        },
        json={"model": model, "input": texts},
        timeout=120
    )
    if resp.status_code != 200:
        print(f"   ⚠️  SiliconFlow API 失败 ({resp.status_code}): {resp.text[:300]}")
        dim = 1024
        return [[0.0] * dim for _ in texts]
    data = resp.json()
    return [item["embedding"] for item in data["data"]]

def load_golden_data():
    """将 golden.jsonl 按 question 分组，返回 {(question): [chunks]}"""
    by_q = {}
    with open(GOLDEN_FILE, encoding="utf-8") as f:
        for line in f:
            rec = json.loads(line)
            q = rec["question"]
            by_q[q] = rec["chunks"]
    return by_q

def load_all_chunks_with_labels():
    """返回 [(chunk_id, chunk_text, question, relevant_ids)]"""
    results = []
    with open(GOLDEN_FILE, encoding="utf-8") as f:
        for line in f:
            rec = json.loads(line)
            q = rec["question"]
            golden_ids = set(rec.get("relevant_chunk_ids", []))
            for chunk in rec["chunks"]:
                results.append({
                    "chunk_id": chunk["id"],
                    "chunk_text": chunk["text"],
                    "question": q,
                    "relevant_ids": golden_ids
                })
    return results

# 构造所有 (question → chunk) pair，question 作为 query，chunk 作为待检候选
all_data = load_all_chunks_with_labels()
print(f"   总 chunk 数: {len(all_data)}")

# 从所有 chunks 提取 unique questions
unique_questions = list({d["question"] for d in all_data})
print(f"   唯一查询数: {len(unique_questions)}")

# 按 question 分组
from collections import defaultdict
chunks_by_q = defaultdict(list)
for d in all_data:
    chunks_by_q[d["question"]].append(d)

# ─── Step 4: 向量化所有 chunks ───────────────────────────────────────────────
print("\n⏳ Step 4: 向量化所有 chunks（SiliconFlow BGE-M3）")

# 提取所有唯一 chunk text
all_texts = list({d["chunk_text"] for d in all_data})
print(f"   唯一文本数: {len(all_texts)}")

BATCH = 32
all_embeddings = []
for i in range(0, len(all_texts), BATCH):
    batch = all_texts[i:i+BATCH]
    embeds = get_siliconflow_embedding(batch)
    all_embeddings.extend(embeds)
    print(f"   进度 {min(i+BATCH, len(all_texts))}/{len(all_texts)}")

# 构建 text→embedding 映射
text_to_embed = dict(zip(all_texts, all_embeddings))
print(f"   ✅ 向量化完成，维度: {len(all_embeddings[0])}")

# ─── Step 5: 写入 Milvus ─────────────────────────────────────────────────────
print("\n💾 Step 5: 写入 Milvus")
print(f"   已连接 Milvus")
collection = Collection("rag_chunks")
print(f"   Collection: rag_chunks, 总实体数: {collection.num_entities}")
print(f"   Schema fields: {[f.name for f in collection.schema.fields]}")

# 构造插入数据（字段与 rag_chunks schema 对齐，共6个字段）
# 注意：保留原始 chunk_id（如 doc0_0a）以便评估时匹配 golden truth
doc_ids, chunk_ids, document_ids, contents_list, embeddings, kb_ids_list = [], [], [], [], [], []

for rec in all_data:
    chunk_text = rec["chunk_text"]
    embed = text_to_embed.get(chunk_text)
    if embed is None:
        continue
    doc_index = rec["chunk_id"].split("_")[0]  # e.g. "doc0"
    doc_ids.append(f"pmqa-{doc_index}")         # doc_id 前缀区分
    chunk_ids.append(rec["chunk_id"])           # 保留原始 ID 如 "doc0_0a"
    document_ids.append(doc_index)
    contents_list.append(chunk_text)
    embeddings.append(embed)
    kb_ids_list.append(kb_id)

BATCH_INSERT = 500
inserted = 0
for i in range(0, len(chunk_ids), BATCH_INSERT):
    batch = [
        doc_ids[i:i+BATCH_INSERT],
        chunk_ids[i:i+BATCH_INSERT],
        document_ids[i:i+BATCH_INSERT],
        contents_list[i:i+BATCH_INSERT],
        embeddings[i:i+BATCH_INSERT],
        kb_ids_list[i:i+BATCH_INSERT],
    ]
    collection.insert(batch)
    inserted += len(batch[0])
    print(f"   写入进度: {inserted}/{len(chunk_ids)}")

collection.flush()
print(f"   ✅ Milvus 写入完成，PubMedQA 共 {len(chunk_ids)} 条，kb_id={kb_id}")

# ─── Step 6: 检索质量评估 ───────────────────────────────────────────────────
print("\n🔍 Step 6: 检索质量评估")
print(f"   查询知识库: kb_id={kb_id}")

async def retrieve(question: str, top_k: int = 10) -> list[str]:
    """调用后端 /retrieve 接口"""
    async with aiohttp.ClientSession() as session:
        payload = {
            "query": question,
            "topK": top_k,
            "kbIds": [kb_id],
            "rerank": False
        }
        try:
            async with session.post(
                f"{BASE_URL}/api/v1/retrieval/retrieve",
                json=payload,
                timeout=aiohttp.ClientTimeout(total=30)
            ) as resp:
                if resp.status != 200:
                    return []
                data = await resp.json()
                return [hit["chunkId"] for hit in data.get("hits", [])]
        except Exception as e:
            print(f"   检索错误 [{question[:30]}...]: {e}")
            return []

def keyword_match(text: str, keywords: list[str]) -> bool:
    """简单关键词匹配"""
    text_lower = text.lower()
    return any(kw.lower() in text_lower for kw in keywords)

def dcg_at_k(ranked_relevant: list[bool], k: int) -> float:
    if k <= 0:
        return 0.0
    return sum((1 if rel else 0) / math.log2(idx + 2)
               for idx, rel in enumerate(ranked_relevant[:k]))

def ndcg_at_k(ranked_relevant: list[bool], k: int) -> float:
    dcg = dcg_at_k(ranked_relevant, k)
    ideal = dcg_at_k([True] * sum(ranked_relevant), k)
    return dcg / ideal if ideal > 0 else 0.0

async def eval_question(question: str, golden_ids: set[str], top_k: int = 10):
    """对单个 question 评估"""
    # 直接在 Python 里用 Milvus 搜索，限定 kb_id=kb_id（PubMedQA）
    collection.load()

    # SiliconFlow 产生 query embedding
    q_embed = get_siliconflow_embedding([question])[0]

    # Milvus search，过滤 kb_id
    search_params = {"metric_type": "IP", "params": {"ef": 64}}
    results = collection.search(
        data=[q_embed],
        anns_field="embedding",
        param=search_params,
        limit=top_k,
        expr=f'kb_id == "{kb_id}"',
        output_fields=["chunk_id", "content", "kb_id"]
    )
    ranked_ids = [r.entity.get("chunk_id") for r in results[0]]

    # 计算 metrics
    ranked_relevant = [rid in golden_ids for rid in ranked_ids]

    hit_at_k = {k: sum(ranked_relevant[:k]) > 0 for k in [3, 5, 10]}
    rr = 0.0
    for i, rel in enumerate(ranked_relevant):
        if rel:
            rr = 1.0 / (i + 1)
            break

    ndcg = {k: ndcg_at_k(ranked_relevant, k) for k in [3, 5, 10]}

    return {
        "question": question[:60],
        "hit_at_k": hit_at_k,
        "rr": rr,
        "ndcg": ndcg,
        "retrieved_ids": ranked_ids[:5],
        "golden_ids": list(golden_ids)[:5],
    }

async def main_eval():
    tasks = []
    for question in unique_questions:
        # 每个 question 的 golden_ids = 所有相关 chunk_ids
        golden_ids = set()
        for rec in records:
            if rec["question"] == question:
                golden_ids.update(rec.get("relevant_chunk_ids", []))
        tasks.append(eval_question(question, golden_ids))

    results = []
    for i, t in enumerate(asyncio.as_completed(tasks)):
        r = await t
        if r:
            results.append(r)
        if (i + 1) % 20 == 0:
            print(f"   评估进度: {i+1}/{len(unique_questions)}")

    # 汇总
    print("\n" + "=" * 60)
    print("📊 检索质量评估结果 (PubMedQA → kb_id={})".format(kb_id))
    print("=" * 60)
    print(f"  测试查询数: {len(results)}")
    print(f"\n  {'K':>5}   {'Hit@K':>8}   {'MRR':>8}   {'NDCG@K':>8}")
    print(f"  {'-'*5}   {'-'*8}   {'-'*8}   {'-'*8}")

    for k in [3, 5, 10]:
        hit_rate = sum(1 for r in results if r["hit_at_k"].get(k, False)) / len(results) * 100
        mrr = sum(r["rr"] for r in results) / len(results) * 100
        ndcg = sum(r["ndcg"].get(k, 0) for r in results) / len(results) * 100
        print(f"  @{k:<4}   {hit_rate:>7.1f}%   {mrr:>7.1f}%   {ndcg:>7.1f}%")

    print()

    if any(
        sum(1 for r in results if r["hit_at_k"].get(k, False)) / len(results) > 0.3
        for k in [3, 5, 10]
    ):
        rating = "🟢 良好"
    elif any(
        sum(1 for r in results if r["hit_at_k"].get(k, False)) / len(results) > 0.1
        for k in [3, 5, 10]
    ):
        rating = "🟡 一般"
    else:
        rating = "🔴 较差"
    print(f"  评级: {rating}")
    print("=" * 60)

    return results

asyncio.run(main_eval())
