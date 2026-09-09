#!/usr/bin/env python3
"""
完整 RAG 检索质量测试 —— 走你们系统的全链路

流程：
  1. 创建/复用测试知识库
  2. 把 PubMedQA 原始文档上传到知识库（走你们 API）
  3. 等待 chunker + IndexService 处理完成
  4. 用 question 检索，走你们的 /retrieve 接口
  5. 内容匹配判断是否命中 golden sentences → 计算 Hit@K / MRR / NDCG
"""
import os
import json, time, requests, aiohttp, asyncio
from pathlib import Path
from datasets import load_dataset
from difflib import SequenceMatcher

BASE_URL = "http://localhost:8081"
SF_API_KEY = os.environ.get("SF_API_KEY", "")
KB_NAME = "PubMedQA全链路测试"
MAX_ITEMS = 5        # 测试多少条 PubMedQA（控制测试规模）
POLL_INTERVAL = 3    # 秒：轮询文档处理状态的间隔
MAX_WAIT = 300       # 秒：单个文档最大等待时间
TOP_K = 10

# ─────────────────────────────────────────────────────────────────────────────
# Step 0: 加载 PubMedQA 数据
# ─────────────────────────────────────────────────────────────────────────────
print("=" * 60)
print("📥 Step 0: 加载 PubMedQA 测试数据")
print("=" * 60)

ds = load_dataset("rungalileo/ragbench", "pubmedqa", split="test", streaming=True)
items = []
for i, item in enumerate(ds):
    items.append(item)
    if i >= MAX_ITEMS - 1:
        break

print(f"  加载了 {len(items)} 条 PubMedQA 数据")
print(f"  每条包含: 1个question + {len(items[0]['documents'])}篇文档")

# 提取每条的 golden sentences 文本（用于内容匹配）
def get_golden_sentences(item: dict) -> list[str]:
    """返回 golden sentence 的完整文本列表"""
    sentences = []
    for doc_idx, doc_sents in enumerate(item["documents_sentences"]):
        for sent_id, sent_text in doc_sents:
            if sent_id in item["all_relevant_sentence_keys"]:
                sentences.append(sent_text.strip())
    return sentences

# 提取每条的所有 sentence 文本（用于判断 chunk 来源）
def get_all_sentences(item: dict) -> list[dict]:
    """返回所有 sentence: {id, text}"""
    result = []
    for doc_idx, doc_sents in enumerate(item["documents_sentences"]):
        for sent_id, sent_text in doc_sents:
            result.append({
                "id": f"doc{doc_idx}_{sent_id}",
                "text": sent_text.strip()
            })
    return result

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: 找到或创建测试知识库
# ─────────────────────────────────────────────────────────────────────────────
print("\n📦 Step 1: 创建/确认测试知识库")
print("=" * 60)

resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == KB_NAME), None)

if kb:
    kb_id = kb["id"]
    print(f"  知识库已存在: id={kb_id}, name={KB_NAME}")
else:
    resp = requests.post(
        f"{BASE_URL}/api/v1/knowledge-base",
        json={
            "name": KB_NAME,
            "description": "PubMedQA 全链路测试知识库",
            "embeddingModel": "BAAI/bge-m3",
            "chunkStrategy": "intelligent"
        }
    )
    kb_id = resp.json()["id"]
    print(f"  知识库创建成功: id={kb_id}")

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: 查现有文档数量
# ─────────────────────────────────────────────────────────────────────────────
resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs", params={"current": 1, "size": 1})
existing_count = resp.json().get("total", 0) if resp.status_code == 200 else 0
print(f"  现有文档数: {existing_count}")

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: 上传文档（走你们系统）
# ─────────────────────────────────────────────────────────────────────────────
print("\n📤 Step 3: 上传 PubMedQA 文档到知识库")
print("=" * 60)

def extract_doc_title(question: str, doc_idx: int, doc_text: str) -> str:
    """从文档内容提取标题"""
    first_line = doc_text.split("\n")[0].strip()
    if len(first_line) > 60:
        first_line = first_line[:60] + "..."
    return f"[{question[:40]}...] doc-{doc_idx}: {first_line}"

def is_likely_uploaded(kb_id: str, title_hint: str, existing_count: int) -> bool:
    """简单判断文档是否已上传过（根据现有数量估算）"""
    # 如果文档总数明显增加了，说明上传成功
    resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs", params={"current": 1, "size": 1})
    if resp.status_code == 200:
        new_count = resp.json().get("total", 0)
        return new_count > existing_count
    return False

uploaded = 0
skipped = 0
current_doc_count = existing_count

for item_idx, item in enumerate(items):
    question = item["question"]

    for doc_idx, doc_text in enumerate(item["documents"]):
        title = extract_doc_title(question, doc_idx, doc_text)
        # 构造文本内容（把句子合并，作为一篇文档）
        content = doc_text.strip()

        files = {"file": (f"pubmedqa_{item_idx}_{doc_idx}.txt", content.encode("utf-8"), "text/plain")}
        data = {"sourceType": "file"}

        try:
            resp = requests.post(
                f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs/upload",
                files=files,
                data=data,
                timeout=60
            )
            if resp.status_code in (200, 201, 202):
                uploaded += 1
            else:
                print(f"  ⚠️  上传失败 [{item_idx}_{doc_idx}]: {resp.status_code} {resp.text[:100]}")
                skipped += 1
        except Exception as e:
            print(f"  ⚠️  上传异常 [{item_idx}_{doc_idx}]: {e}")
            skipped += 1

        current_doc_count += 1

    if (item_idx + 1) % 10 == 0:
        print(f"  进度: {item_idx + 1}/{len(items)} 条 PubMedQA，已上传 {uploaded} 篇文档")

print(f"\n  ✅ 上传完成: {uploaded} 篇文档上传，{skipped} 篇跳过")

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: 等待文档处理完成
# ─────────────────────────────────────────────────────────────────────────────
print("\n⏳ Step 4: 等待文档处理（chunk + index）")
print("=" * 60)

async def wait_for_doc_processing():
    """轮询文档状态，直到 chunk count 稳定（或超时）"""
    last_count = 0
    stable_count = 0
    max_wait = MAX_WAIT

    start = time.time()
    while time.time() - start < max_wait:
        elapsed = int(time.time() - start)
        resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs", params={"current": 1, "size": 1})
        if resp.status_code == 200:
            doc_count = resp.json().get("total", 0)
        else:
            doc_count = 0

        # 估算 chunk 数量（每篇文档约 3-10 个 chunk）
        # 更准确：直接查 Milvus
        from pymilvus import connections, Collection
        connections.connect(host="localhost", port="29530", alias="default")
        c = Collection("rag_chunks")
        c.load()
        results = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
        chunk_count = len(results)

        print(f"  [{elapsed}s] 文档: {doc_count}, Milvus chunks(kb={kb_id}): {chunk_count}", end="")

        if doc_count >= uploaded and chunk_count >= uploaded * 2:
            print(" → 处理完成！")
            return True

        print()
        await asyncio.sleep(POLL_INTERVAL)

    print(f"  ⚠️  等待超时（>{max_wait}s），继续下一步")
    return False

asyncio.run(wait_for_doc_processing())

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: 检索质量评估（内容匹配）
# ─────────────────────────────────────────────────────────────────────────────
print("\n🔍 Step 5: 检索质量评估（内容匹配 golden sentences）")
print("=" * 60)

def content_match_ratio(chunk_text: str, sentence_text: str) -> float:
    """判断 chunk_text 是否包含 sentence_text（模糊匹配）"""
    chunk_lower = chunk_text.lower()
    sent_lower = sentence_text.lower()

    # 精确包含
    if sent_lower in chunk_lower:
        return 1.0

    # 模糊匹配：SentenceMatcher 看重叠程度
    ratio = SequenceMatcher(None, sent_lower, chunk_lower).ratio()
    return ratio

def chunk_matches_golden(chunk_text: str, golden_sentences: list[str], threshold: float = 0.75) -> bool:
    """chunk 是否包含/匹配了任意一个 golden sentence"""
    for gs in golden_sentences:
        if len(gs) < 20:  # 太短的 golden sentence 跳过
            continue
        ratio = content_match_ratio(chunk_text, gs)
        if ratio >= threshold:
            return True
    return False

async def eval_question(question: str, golden_sentences: list[str]) -> dict:
    """检索单个 question，用内容匹配判断命中"""
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

    # 检查每个返回的 chunk 是否包含 golden sentence
    ranked_hits = []
    for r in results:
        content = r.get("content", "")
        matched = chunk_matches_golden(content, golden_sentences)
        ranked_hits.append(matched)

    # 计算 metrics
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

    return {
        "question": question[:50],
        "hit_at_k": hit_at_k,
        "rr": rr,
        "ndcg": ndcg,
        "results_count": len(results),
    }

async def main_eval():
    tasks = []
    for item in items:
        golden = get_golden_sentences(item)
        tasks.append(eval_question(item["question"], golden))

    all_results = []
    for i, t in enumerate(asyncio.as_completed(tasks)):
        r = await t
        if r:
            all_results.append(r)
        if (i + 1) % 10 == 0:
            print(f"  评估进度: {i + 1}/{len(items)}")

    # 汇总
    print("\n" + "=" * 60)
    print(f"📊 检索质量评估结果（走你们的全链路）")
    print(f"   知识库: {KB_NAME} (kb_id={kb_id})")
    print(f"   分块策略: 你们的 intelligent chunker")
    print(f"   向量化: SiliconFlow BAAI/bge-m3 (1024维)")
    print(f"   测试查询数: {len(all_results)}")
    print()
    print(f"   {'K':>5}   {'Hit@K':>8}   {'MRR':>8}   {'NDCG@K':>8}")
    print(f"   {'-'*5}   {'-'*8}   {'-'*8}   {'-'*8}")

    for k in [3, 5, 10]:
        hit_rate = sum(r["hit_at_k"].get(k, 0) for r in all_results) / len(all_results) * 100
        mrr = sum(r["rr"] for r in all_results) / len(all_results) * 100
        ndcg = sum(r["ndcg"].get(k, 0) for r in all_results) / len(all_results) * 100
        print(f"   @{k:<4}   {hit_rate:>7.1f}%   {mrr:>7.1f}%   {ndcg:>7.1f}%")

    print()
    if any(sum(r["hit_at_k"].get(k, 0) for r in all_results) / len(all_results) > 0.6 for k in [3, 5, 10]):
        rating = "🟢 良好"
    elif any(sum(r["hit_at_k"].get(k, 0) for r in all_results) / len(all_results) > 0.3 for k in [3, 5, 10]):
        rating = "🟡 一般"
    else:
        rating = "🔴 较差"
    print(f"   评级: {rating}")
    print("=" * 60)

    return all_results

asyncio.run(main_eval())
