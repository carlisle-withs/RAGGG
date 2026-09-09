#!/usr/bin/env python3
"""
混合检索压测 —— 对比三种检索模式

模式：
  1. vector:  /api/v1/retrieve    （纯 Milvus 向量）
  2. es:      直接调 ES BM25       （纯关键字）
  3. hybrid:  /api/v1/retrieve/hybrid（向量+ES+RRF+重排）

用法：
  python benchmark_hybrid.py                  # 默认 10 条
  python benchmark_hybrid.py --items 20       # 20 条
  python benchmark_hybrid.py --retrieve-only   # 不重新 ingest，只测现有数据
"""
import os
import argparse
import asyncio
import time
import statistics
import requests
import json
import aiohttp
from difflib import SequenceMatcher
from pymilvus import connections, Collection

BASE_URL = "http://localhost:8081"
ES_URL = "http://localhost:29201"
SF_API_KEY = os.environ.get("SF_API_KEY", "")
KB_NAME = "PubMedQA混合检索测试"
MAX_ITEMS = 10
TOP_K = 10

# ─────────────────────────────────────────────────────────────────────────────
# 解析参数
# ─────────────────────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="混合检索压测")
parser.add_argument("--items", type=int, default=MAX_ITEMS, help="PubMedQA 条目数")
parser.add_argument("--retrieve-only", action="store_true", help="仅测检索，不重新 ingest")
parser.add_argument("--rewrite", action="store_true", help="用 LLM 将 query 改写得更模糊后再检索")
args = parser.parse_args()
MAX_ITEMS = args.items

# ─────────────────────────────────────────────────────────────────────────────
# Step 0: 加载本地 PubMedQA
# ─────────────────────────────────────────────────────────────────────────────
print("=" * 60)
print(f"Step 0: 加载 {MAX_ITEMS} 条 PubMedQA")
print("=" * 60)

LOCAL_FILE = "data/pubmedqa_golden.jsonl"
items = []
with open(LOCAL_FILE, encoding="utf-8") as f:
    for i, line in enumerate(f):
        if i >= MAX_ITEMS:
            break
        items.append(json.loads(line)["raw_item"])
print(f"  加载完成: {len(items)} 条，每条 {len(items[0]['documents'])} 篇文档")

def get_golden(item):
    sents = []
    for di, ds in enumerate(item["documents_sentences"]):
        for sid, st in ds:
            if sid in item["all_relevant_sentence_keys"]:
                sents.append(st.strip())
    return sents

def content_ratio(chunk_text, sent_text):
    cl = chunk_text.lower(); sl = sent_text.lower()
    if sl in cl: return 1.0
    return SequenceMatcher(None, sl, cl).ratio()

def chunk_matches(chunk_text, golden, threshold=0.75):
    for g in golden:
        if len(g) < 20: continue
        if content_ratio(chunk_text, g) >= threshold: return True
    return False

# ─────────────────────────────────────────────────────────────────────────────
# Query 改写函数：把原始问题改写成更模糊/口语化/同义替换的版本
# ─────────────────────────────────────────────────────────────────────────────
LLM_MODEL = "Qwen/Qwen2.5-7B-Instruct"

def rewrite_query_fuzzy(original_query: str) -> str:
    """用 LLM 将原始 query 改写成更模糊的版本"""
    prompt = f"""把以下医学研究问题改写成更模糊、口语化的表达。
要求：
1. 换个说法，但保留大致含义
2. 可以去掉专有名词，用更口语的词替代
3. 可以用反问、猜测、模糊描述
4. 不要出现原文中的精确术语

原始问题：{original_query}

改写后（更模糊、口语化的版本）："""

    try:
        resp = requests.post(
            "https://api.siliconflow.cn/v1/chat/completions",
            headers={"Authorization": f"Bearer {SF_API_KEY}", "Content-Type": "application/json"},
            json={
                "model": LLM_MODEL,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.95,
                "max_tokens": 150
            },
            timeout=20
        )
        if resp.status_code == 200:
            rewritten = resp.json()["choices"][0]["message"]["content"].strip()
            return rewritten
        else:
            return original_query
    except Exception as e:
        print(f"  改写失败: {e}, 使用原始 query")
        return original_query

# ─────────────────────────────────────────────────────────────────────────────
# Step 0.5: Query 改写（--rewrite 模式）
# ─────────────────────────────────────────────────────────────────────────────
if args.rewrite:
    print("\nStep 0.5: Query 改写（模糊化）")
    print("=" * 60)
    for i, item in enumerate(items):
        original_q = item["question"]
        rewritten_q = rewrite_query_fuzzy(original_q)
        item["rewritten_question"] = rewritten_q
        print(f"  [{i+1}] 原文: {original_q[:60]}...")
        print(f"       改写: {rewritten_q[:60]}...")
        print()
    print(f"  改写完成: {len(items)} 条")

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: 确认知识库并准备数据
# ─────────────────────────────────────────────────────────────────────────────
print("\nStep 1: 确认知识库")
print("=" * 60)

resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == KB_NAME), None)

if args.retrieve_only:
    if not kb:
        print("  [ERROR] 未找到知识库")
        exit(1)
    kb_id = kb["id"]
    print(f"  复用 kb_id={kb_id}")
else:
    if not kb:
        resp = requests.post(f"{BASE_URL}/api/v1/knowledge-base", json={
            "name": KB_NAME, "description": "PubMedQA 混合检索测试",
            "embeddingModel": "BAAI/bge-m3", "chunkStrategy": "intelligent"
        })
        kb_id = resp.json()["id"]
        print(f"  创建 kb_id={kb_id}")
    else:
        kb_id = kb["id"]
        print(f"  复用 kb_id={kb_id}")

    # 清空旧数据
    print("\n  清空 Milvus 旧数据...")
    connections.connect(host="localhost", port="29530", alias="default")
    c = Collection("rag_chunks")
    c.load()
    old = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
    for chunk in old:
        try: c.delete(expr=f'chunk_id == "{chunk["chunk_id"]}"')
        except: pass
    c.flush()
    c.release()
    c.load()
    print(f"  已清空 {len(old)} 个旧 chunks")

    # 上传文档
    print(f"\n  上传 {len(items)*len(items[0]['documents'])} 篇文档...")
    uploaded = 0
    for item_idx, item in enumerate(items):
        for doc_idx, doc_text in enumerate(item["documents"]):
            content = doc_text.strip()
            resp = requests.post(
                f"{BASE_URL}/api/v1/knowledge-base/{kb_id}/docs/upload",
                files={"file": (f"pubmedqa_{item_idx}_{doc_idx}.txt", content.encode("utf-8"), "text/plain")},
                data={"sourceType": "file"}, timeout=60
            )
            if resp.status_code in (200, 201, 202): uploaded += 1
    print(f"  上传完成: {uploaded} 篇")

    # 等待处理
    print("\n  等待 chunk + index 完成...")
    wait_start = time.time()
    while time.time() - wait_start < 300:
        connections.connect(host="localhost", port="29530", alias="default")
        c = Collection("rag_chunks")
        c.load()
        chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
        elapsed = int(time.time() - wait_start)
        print(f"  [{elapsed}s] chunks: {len(chunks)}")
        if len(chunks) >= uploaded * 0.5:
            time.sleep(3)
            chunks2 = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
            if len(chunks2) == len(chunks):
                print(f"  处理完成: {len(chunks)} chunks")
                c.release()
                break
        c.release()
        time.sleep(3)

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: 确认 ES 中 kb_id=kb_id 的 chunk 数量
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 2: 检查 ES 索引状态")
print("=" * 60)

es_index = "rag_documents"
try:
    resp = requests.get(f"{ES_URL}/{es_index}/_count", timeout=5)
    total_es = resp.json().get("count", 0)
    print(f"  ES 索引总量: {total_es}")
except Exception as e:
    print(f"  ES 查询失败: {e}")
    total_es = 0

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: 三种模式检索延迟测试
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 3: 检索延迟压测（三种模式）")
print("=" * 60)

vector_times = []
hybrid_times = []
es_times = []

async def eval_all():
    for item_idx, item in enumerate(items):
        q = item["rewritten_question"] if "rewritten_question" in item else item["question"]
        golden = get_golden(item)

        # --- 模式1: 纯向量 ---
        t0 = time.time()
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{BASE_URL}/api/v1/retrieve",
                    json={"query": q, "kbIds": [kb_id], "topK": TOP_K},
                    timeout=aiohttp.ClientTimeout(total=30)
                ) as resp:
                    vector_elapsed = time.time() - t0
                    if resp.status == 200:
                        data = await resp.json()
                        vector_results = data.get("results", [])
                        vector_times.append(vector_elapsed)
        except Exception as e:
            print(f"  [{item_idx}] vector 错误: {e}")

        # --- 模式2: hybrid ---
        t0 = time.time()
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{BASE_URL}/api/v1/retrieve/hybrid",
                    json={"query": q, "kbIds": [kb_id], "topK": TOP_K},
                    timeout=aiohttp.ClientTimeout(total=30)
                ) as resp:
                    hybrid_elapsed = time.time() - t0
                    if resp.status == 200:
                        data = await resp.json()
                        hybrid_results = data.get("results", [])
                        hybrid_times.append(hybrid_elapsed)
        except Exception as e:
            print(f"  [{item_idx}] hybrid 错误: {e}")

        # --- 模式3: 直接 ES BM25 ---
        t0 = time.time()
        try:
            # ES index name 从 application.yml 获取，默认 rag_documents
            resp = requests.post(
                f"{ES_URL}/{es_index}/_search",
                json={
                    "query": {"bool": {"must": [
                        {"match": {"content": q}},
                        {"term": {"kb_id": kb_id}}
                    ]}},
                    "size": TOP_K,
                    "_source": ["id", "content"]
                },
                timeout=10,
                headers={"Content-Type": "application/json"}
            )
            es_elapsed = time.time() - t0
            if resp.status_code == 200:
                es_times.append(es_elapsed)
        except Exception as e:
            print(f"  [{item_idx}] ES 错误: {e}")

        if (item_idx + 1) % 5 == 0:
            print(f"  进度: {item_idx+1}/{len(items)}")

asyncio.run(eval_all())

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: 质量测试（Hit@K）
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 4: 检索质量测试（三种模式 Hit@K）")
print("=" * 60)

def eval_mode(mode_name, fetch_fn):
    all_hits = {3: [], 5: [], 10: []}
    all_rr = []
    for item in items:
        q = item["rewritten_question"] if "rewritten_question" in item else item["question"]
        golden = get_golden(item)
        try:
            results = fetch_fn(q)
            hits = [chunk_matches(r.get("content", ""), golden) for r in results]
            for k in [3, 5, 10]:
                all_hits[k].append(int(any(hits[:k])))
            rr = 0.0
            for i, h in enumerate(hits):
                if h: rr = 1.0 / (i + 1); break
            all_rr.append(rr)
        except Exception as e:
            print(f"  {mode_name} 错误: {e}")
            for k in [3, 5, 10]: all_hits[k].append(0)
            all_rr.append(0.0)
    N = len(all_hits[3])
    return {k: sum(all_hits[k])/N*100 for k in [3,5,10]}, sum(all_rr)/N*100

def get_vector_results(q):
    r = requests.post(f"{BASE_URL}/api/v1/retrieve",
        json={"query": q, "kbIds": [kb_id], "topK": TOP_K}, timeout=30)
    return r.json().get("results", [])

def get_hybrid_results(q):
    r = requests.post(f"{BASE_URL}/api/v1/retrieve/hybrid",
        json={"query": q, "kbIds": [kb_id], "topK": TOP_K}, timeout=30)
    return r.json().get("results", [])

def get_es_results(q):
    r = requests.post(f"{ES_URL}/{es_index}/_search",
        json={
            "query": {"bool": {"must": [
                {"match": {"content": q}},
                {"term": {"kb_id": kb_id}}
            ]}},
            "size": TOP_K,
            "_source": ["id", "content"]
        }, timeout=10, headers={"Content-Type": "application/json"})
    if r.status_code != 200: return []
    return [{"content": h["_source"]["content"]} for h in r.json()["hits"]["hits"]]

print(f"  测试中...")
v_stats, v_mrr = eval_mode("vector", get_vector_results)
h_stats, h_mrr = eval_mode("hybrid", get_hybrid_results)
e_stats, e_mrr = eval_mode("ES", get_es_results)

# ─────────────────────────────────────────────────────────────────────────────
# 汇总
# ─────────────────────────────────────────────────────────────────────────────
print("\n" + "=" * 60)
print("混合检索对比结果")
print("=" * 60)
print(f"  测试规模: {MAX_ITEMS} 条 PubMedQA")
print(f"  知识库:   kb_id={kb_id}")
print(f"  ES chunks 总量: {total_es}")
if args.rewrite:
    print(f"  Query模式: LLM改写（模糊化）")
else:
    print(f"  Query模式: 原始PubMedQA question")
print()
print(f"  【延迟】")
print(f"  {'模式':<10} {'平均':>8} {'p50':>8} {'p95':>8} {'p99':>8} {'最大':>8}")
print(f"  {'-'*10} {'-'*8} {'-'*8} {'-'*8} {'-'*8} {'-'*8}")

def percentile(data, p):
    if not data: return 0
    s = sorted(data)
    return s[int(len(s)*p/100)]

def print_latency(name, times):
    if not times: print(f"  {name:<10} {'N/A':>8}"); return
    print(f"  {name:<10} {statistics.mean(times)*1000:>7.0f}ms {percentile(times,50)*1000:>7.0f}ms {percentile(times,95)*1000:>7.0f}ms {percentile(times,99)*1000:>7.0f}ms {max(times)*1000:>7.0f}ms")

print_latency("vector", vector_times)
print_latency("hybrid", hybrid_times)
print_latency("ES-only", es_times)

print()
print(f"  【质量 Hit@K】")
print(f"  {'模式':<10} {'Hit@3':>8} {'Hit@5':>8} {'Hit@10':>8} {'MRR':>8}")
print(f"  {'-'*10} {'-'*8} {'-'*8} {'-'*8} {'-'*8}")
print(f"  {'vector':<10} {v_stats[3]:>7.1f}% {v_stats[5]:>7.1f}% {v_stats[10]:>7.1f}% {v_mrr:>7.1f}%")
print(f"  {'hybrid':<10} {h_stats[3]:>7.1f}% {h_stats[5]:>7.1f}% {h_stats[10]:>7.1f}% {h_mrr:>7.1f}%")
print(f"  {'ES-only':<10} {e_stats[3]:>7.1f}% {e_stats[5]:>7.1f}% {e_stats[10]:>7.1f}% {e_mrr:>7.1f}%")
print("=" * 60)
