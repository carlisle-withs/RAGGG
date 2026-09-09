#!/usr/bin/env python3
"""
RAG 性能压测脚本

测试内容：
  1. 上传文档耗时（单篇 / 批量）
  2. 分块 + 向量化耗时（从 Milvus chunk count 反推）
  3. 检索延迟（p50 / p95 / p99）
  4. Milvus 查询耗时

用法：
  python benchmark_perf.py                          # 默认 10 条 PubMedQA
  python benchmark_perf.py --items 50               # 测 50 条
  python benchmark_perf.py --retrieve-only           # 只测检索（不重新上传）
"""
import os
import argparse
import asyncio
import time
import statistics
import requests
import aiohttp
from pathlib import Path
from difflib import SequenceMatcher
from pymilvus import connections, Collection

BASE_URL = "http://localhost:8081"
SF_API_KEY = os.environ.get("SF_API_KEY", "")
KB_NAME = "PubMedQA性能测试"
MAX_ITEMS = 10
TOP_K = 10
EMBEDDING_BATCH_SIZE = 8

# ─────────────────────────────────────────────────────────────────────────────
# 解析参数
# ─────────────────────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="RAG 性能压测")
parser.add_argument("--items", type=int, default=MAX_ITEMS, help="PubMedQA 条目数")
parser.add_argument("--retrieve-only", action="store_true", help="仅测检索，不重新上传")
args = parser.parse_args()
MAX_ITEMS = args.items

# ─────────────────────────────────────────────────────────────────────────────
# Step 0: 加载 PubMedQA
# ─────────────────────────────────────────────────────────────────────────────
print("=" * 60)
print(f"Step 0: 加载 {MAX_ITEMS} 条 PubMedQA")
print("=" * 60)

import json
LOCAL_FILE = Path(__file__).parent / "data" / "pubmedqa_golden.jsonl"
items = []
with open(LOCAL_FILE, encoding="utf-8") as f:
    for i, line in enumerate(f):
        if i >= MAX_ITEMS:
            break
        raw = json.loads(line)
        # 用 raw_item（HuggingFace 原格式）作为 item
        items.append(raw["raw_item"])
print(f"  从本地加载 {len(items)} 条，每条 {len(items[0]['documents'])} 篇文档，共 {len(items) * len(items[0]['documents'])} 篇")

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: 准备知识库
# ─────────────────────────────────────────────────────────────────────────────
print("\nStep 1: 确认知识库")
print("=" * 60)

resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == KB_NAME), None)

if args.retrieve_only:
    if not kb:
        print("  [ERROR] --retrieve-only 但未找到知识库")
        exit(1)
    kb_id = kb["id"]
    print(f"  复用已有知识库: id={kb_id}")
else:
    if not kb:
        resp = requests.post(f"{BASE_URL}/api/v1/knowledge-base", json={
            "name": KB_NAME, "description": "PubMedQA 性能测试",
            "embeddingModel": "BAAI/bge-m3", "chunkStrategy": "intelligent"
        })
        kb_id = resp.json()["id"]
        print(f"  创建知识库: id={kb_id}")
    else:
        kb_id = kb["id"]
        print(f"  复用已有知识库: id={kb_id}")

    # 清空旧 chunks
    print("\n  清空 Milvus 旧数据...")
    connections.connect(host="localhost", port="29530", alias="default")
    c = Collection("rag_chunks")
    c.load()
    old = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
    for chunk in old:
        try:
            c.delete(expr=f'chunk_id == "{chunk["chunk_id"]}"')
        except Exception:
            pass
    c.flush()
    c.release()
    c.load()
    print(f"  清空完成: {len(old)} 个旧 chunk")

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: 上传文档 + 记录耗时
# ─────────────────────────────────────────────────────────────────────────────
if not args.retrieve_only:
    print(f"\nStep 2: 上传 {len(items) * len(items[0]['documents'])} 篇文档")
    print("=" * 60)

    upload_times = []  # 每篇文档上传耗时（秒）
    total_upload_start = time.time()

    for item_idx, item in enumerate(items):
        question = item["question"]
        for doc_idx, doc_text in enumerate(item["documents"]):
            content = doc_text.strip()
            t0 = time.time()
            try:
                resp = requests.post(
                    f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs/upload",
                    files={"file": (f"pubmedqa_{item_idx}_{doc_idx}.txt", content.encode("utf-8"), "text/plain")},
                    data={"sourceType": "file"},
                    timeout=60
                )
                elapsed = time.time() - t0
                upload_times.append(elapsed)
            except Exception as e:
                print(f"  上传失败 [{item_idx}_{doc_idx}]: {e}")
                upload_times.append(60.0)  # 超时记 60s

        if (item_idx + 1) % 5 == 0:
            print(f"  进度: {item_idx+1}/{len(items)} 条 PubMedQA，上传耗时累计 {time.time()-total_upload_start:.1f}s")

    total_upload = time.time() - total_upload_start
    avg_upload = statistics.mean(upload_times)
    print(f"\n  上传完成:")
    print(f"    总耗时:       {total_upload:.2f}s")
    print(f"    平均单篇:     {avg_upload*1000:.1f}ms")
    print(f"    吞吐量:       {len(upload_times)/total_upload:.2f} 篇/秒")

    # 排序以便计算百分位
    upload_times_sorted = sorted(upload_times)
    def percentile(data, p):
        idx = int(len(data) * p / 100)
        return data[min(idx, len(data)-1)]

    print(f"    p50:          {percentile(upload_times_sorted, 50)*1000:.1f}ms")
    print(f"    p95:          {percentile(upload_times_sorted, 95)*1000:.1f}ms")
    print(f"    p99:          {percentile(upload_times_sorted, 99)*1000:.1f}ms")
    print(f"    最大:          {max(upload_times)*1000:.1f}ms")

    # ─────────────────────────────────────────────────────────────────────────
    # Step 3: 等待 chunk + index 完成
    # ─────────────────────────────────────────────────────────────────────────
    print(f"\nStep 3: 等待文档处理完成（分块+向量化+索引）")
    print("=" * 60)

    connections.connect(host="localhost", port="29530", alias="default")
    c = Collection("rag_chunks")
    c.load()

    wait_start = time.time()
    last_chunk_count = 0
    POLL_INTERVAL = 2
    MAX_WAIT = 600

    while time.time() - wait_start < MAX_WAIT:
        elapsed = int(time.time() - wait_start)
        results = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
        chunk_count = len(results)

        print(f"  [{elapsed}s] chunks: {chunk_count}", end="")
        if chunk_count > last_chunk_count:
            print(f"  (+{chunk_count - last_chunk_count})")
            last_chunk_count = chunk_count
        else:
            print()

        # 连续 3 次不增长认为完成
        if chunk_count >= len(items) * len(items[0]["documents"]) * 0.5 and chunk_count == last_chunk_count:
            # 等一下再确认
            time.sleep(POLL_INTERVAL * 2)
            results2 = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
            if len(results2) == chunk_count:
                print(f"\n  处理完成！共 {chunk_count} 个 chunks，耗时 {time.time()-wait_start:.1f}s")
                break
        elif elapsed > 10 and chunk_count == 0:
            print("  警告: 长时间无 chunks，可能处理失败")

    c.release()
    c.load()

    total_process_time = total_upload + (time.time() - wait_start)
    results = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
    chunk_count = len(results)

    print(f"\n  全流程耗时（含上传+处理）: {total_process_time:.2f}s")
    print(f"  平均每篇文档总耗时:        {total_process_time / (len(items) * len(items[0]["documents"])):.2f}s")
    print(f"  吞吐量:                   {(len(items) * len(items[0]["documents"])) / total_process_time:.2f} 篇/秒")

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: 检索延迟测试
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 4: 检索延迟压测")
print("=" * 60)

def get_golden_sentences(item: dict) -> list[str]:
    sentences = []
    for doc_idx, doc_sents in enumerate(item["documents_sentences"]):
        for sent_id, sent_text in doc_sents:
            if sent_id in item["all_relevant_sentence_keys"]:
                sentences.append(sent_text.strip())
    return sentences

retrieve_times = []  # /retrieve API 耗时（秒）
milvus_times = []    # Milvus 查询耗时（秒）
all_results = []

async def retrieve_one(question: str, golden: list[str]):
    t0 = time.time()
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{BASE_URL}/api/v1/retrieve",
                json={"query": question, "kbIds": [kb_id], "topK": TOP_K},
                timeout=aiohttp.ClientTimeout(total=30)
            ) as resp:
                retrieve_elapsed = time.time() - t0
                if resp.status == 200:
                    data = await resp.json()
                    results = data.get("results", [])
                    retrieve_times.append(retrieve_elapsed)
                    return results
    except Exception as e:
        print(f"  检索错误: {e}")
    return []

async def benchmark_retrieval():
    tasks = [retrieve_one(item["question"], get_golden_sentences(item)) for item in items]
    results_list = []
    for i, t in enumerate(asyncio.as_completed(tasks)):
        r = await t
        results_list.append(r)
        if (i + 1) % 5 == 0:
            print(f"  检索进度: {i+1}/{len(items)}")
    return results_list

results_list = asyncio.run(benchmark_retrieval())

# 打印检索延迟统计
print(f"\n  检索延迟统计（{len(items)} 个 query）:")
if retrieve_times:
    retrieve_times_sorted = sorted(retrieve_times)
    def percentile(data, p):
        idx = int(len(data) * p / 100)
        return data[min(idx, len(data)-1)]
    print(f"    平均:      {statistics.mean(retrieve_times)*1000:.1f}ms")
    print(f"    p50:       {percentile(retrieve_times_sorted, 50)*1000:.1f}ms")
    print(f"    p95:       {percentile(retrieve_times_sorted, 95)*1000:.1f}ms")
    print(f"    p99:       {percentile(retrieve_times_sorted, 99)*1000:.1f}ms")
    print(f"    最大:       {max(retrieve_times)*1000:.1f}ms")
    print(f"    最小:       {min(retrieve_times)*1000:.1f}ms")
    print(f"    标准差:     {statistics.stdev(retrieve_times)*1000:.1f}ms" if len(retrieve_times) > 1 else "")

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: Milvus 直接查询延迟
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 5: Milvus HNSW 查询延迟（直接测向量检索）")
print("=" * 60)

from pymilvus import DataType

# 获取一条 query 的 embedding
def embed_text(text: str) -> list[float]:
    resp = requests.post(
        "https://api.siliconflow.cn/v1/embeddings",
        headers={"Authorization": f"Bearer {SF_API_KEY}", "Content-Type": "application/json"},
        json={"model": "BAAI/bge-m3", "input": text[:2000]},
        timeout=30
    )
    return resp.json()["data"][0]["embedding"]

connections.connect(host="localhost", port="29530", alias="default")
c = Collection("rag_chunks")
c.load()

milvus_query_times = []
for i, item in enumerate(items[:min(10, len(items))]):
    try:
        query_emb = embed_text(item["question"][:500])
        t0 = time.time()
        result = c.search(
            data=[query_emb],
            anns_field="embedding",
            param={"metric_type": "IP", "params": {"ef": 64}},
            limit=TOP_K,
            expr=f'kb_id == "{kb_id}"',
            output_fields=["chunk_id", "content"]
        )
        milvus_query_times.append(time.time() - t0)
    except Exception as e:
        print(f"  Milvus 查询失败: {e}")

if milvus_query_times:
    milvus_sorted = sorted(milvus_query_times)
    print(f"  Milvus 查询延迟（{len(milvus_query_times)} 次）:")
    print(f"    平均:      {statistics.mean(milvus_query_times)*1000:.1f}ms")
    print(f"    p50:       {milvus_sorted[int(len(milvus_query_times)*0.5)]:.1f}ms")
    print(f"    p95:       {milvus_sorted[int(len(milvus_query_times)*0.95)]:.1f}ms")
    print(f"    p99:       {milvus_sorted[int(len(milvus_query_times)*0.99)]:.1f}ms")
    print(f"    最大:       {max(milvus_query_times)*1000:.1f}ms")

c.release()

# ─────────────────────────────────────────────────────────────────────────────
# 汇总
# ─────────────────────────────────────────────────────────────────────────────
print("\n" + "=" * 60)
print("性能压测汇总")
print("=" * 60)
print(f"  测试规模:     {MAX_ITEMS} 条 PubMedQA ({MAX_ITEMS*5} 篇文档)")
print(f"  向量模型:     BAAI/bge-m3 (1024维)")
print(f"  检索 topK:    {TOP_K}")
print()
if not args.retrieve_only:
    print(f"  [上传]  平均: {avg_upload*1000:.0f}ms/篇  p95: {percentile(upload_times_sorted, 95)*1000:.0f}ms  吞吐: {len(upload_times)/total_upload:.1f} 篇/s")
if retrieve_times:
    print(f"  [检索]  平均: {statistics.mean(retrieve_times)*1000:.0f}ms    p95: {percentile(retrieve_times_sorted, 95)*1000:.0f}ms")
if milvus_query_times:
    print(f"  [Milvus] 平均: {statistics.mean(milvus_query_times)*1000:.0f}ms    p95: {milvus_sorted[int(len(milvus_query_times)*0.95)]:.0f}ms")
print("=" * 60)
