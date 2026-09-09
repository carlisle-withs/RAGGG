#!/usr/bin/env python3
"""
中文查询跨语言检索测试

知识库：英文 chunks（已存在）
查询：用 LLM 把 PubMedQA 原问题翻译成中文
评估：中文 query 是否能召回正确的英文 chunks

这测试的是：BGE-M3 向量模型的多语言能力
"""
import os
import json, time, requests, aiohttp, asyncio
from difflib import SequenceMatcher
from pymilvus import connections, Collection

BASE_URL = "http://localhost:8081"
ES_URL = "http://localhost:29201"
SF_API_KEY = os.environ.get("SF_API_KEY", "")
LOCAL_FILE = "data/pubmedqa_golden.jsonl"
KB_NAME = "PubMedQA全链路测试"
ES_INDEX = "rag_documents"
LLM_MODEL = "Qwen/Qwen2.5-7B-Instruct"
MAX_ITEMS = 20
TOP_K = 10

# ─────────────────────────────────────────────────────────────────────────────
# 加载本地数据
# ─────────────────────────────────────────────────────────────────────────────
print("=" * 60)
print("加载 PubMedQA 数据")
print("=" * 60)

with open(LOCAL_FILE, encoding="utf-8") as f:
    raw_items = [json.loads(line) for line in f]

items = raw_items[:MAX_ITEMS]
print(f"  加载 {len(items)} 条，每条 {len(items[0]['raw_item']['documents'])} 篇文档")

def get_golden(item):
    """返回 golden sentence 原文（用于内容匹配）"""
    sents = []
    raw = item["raw_item"]
    for di, ds in enumerate(raw["documents_sentences"]):
        for sid, st in ds:
            if sid in raw["all_relevant_sentence_keys"]:
                sents.append(st.strip())
    return sents

def translate_to_chinese(text: str) -> str:
    """英译中，口语化"""
    prompt = f"""将以下英文翻译成自然流畅的中文口语化表达。
意译不要直译，保持医学含义但表达更自然：

{text[:500]}

中文:"""
    try:
        resp = requests.post(
            "https://api.siliconflow.cn/v1/chat/completions",
            headers={"Authorization": f"Bearer {SF_API_KEY}", "Content-Type": "application/json"},
            json={"model": LLM_MODEL, "messages": [{"role": "user", "content": prompt}], "temperature": 0.5, "max_tokens": 200},
            timeout=20
        )
        if resp.status_code == 200:
            return resp.json()["choices"][0]["message"]["content"].strip()
    except:
        pass
    return text

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
# Step 0.5: 找 kb_id
# ─────────────────────────────────────────────────────────────────────────────
print("\n确认知识库")
print("=" * 60)
resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == KB_NAME), None)
if not kb:
    print("  未找到 PubMedQA全链路测试 知识库")
    exit(1)
kb_id = kb["id"]
print(f"  kb_id={kb_id}")

connections.connect(host="localhost", port="29530", alias="default")
c = Collection("rag_chunks")
c.load()
milvus_chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
print(f"  Milvus chunks: {len(milvus_chunks)}")

resp = requests.post(f"{ES_URL}/{ES_INDEX}/_search",
    headers={"Content-Type": "application/json"},
    json={"query": {"term": {"kb_id": kb_id}}, "size": 0}, timeout=10)
es_count = resp.json().get("hits", {}).get("total", {}).get("value", 0)
print(f"  ES chunks: {es_count}")
c.release()

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: 生成中文 query
# ─────────────────────────────────────────────────────────────────────────────
print("\nStep 1: 将英文问题翻译为中文")
print("=" * 60)

for i, item in enumerate(items):
    raw = item["raw_item"]
    chinese_q = translate_to_chinese(raw["question"])
    item["chinese_question"] = chinese_q
    print(f"  [{i+1}] EN: {raw['question'][:50]}...")
    print(f"       ZH: {chinese_q[:50]}...")
    time.sleep(0.3)

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: 三种模式检索 + 质量评估
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 2: 三种模式检索 + 质量评估")
print("=" * 60)

vector_hits = {3: [], 5: [], 10: []}
hybrid_hits = {3: [], 5: [], 10: []}
es_hits = {3: [], 5: [], 10: []}
vector_rr = []; hybrid_rr = []; es_rr = []

for i, item in enumerate(items):
    raw = item["raw_item"]
    en_q = raw["question"]
    zh_q = item["chinese_question"]
    golden = get_golden(item)

    # --- vector ---
    try:
        r = requests.post(f"{BASE_URL}/api/v1/retrieve",
            json={"query": zh_q, "kbIds": [kb_id], "topK": TOP_K}, timeout=30)
        if r.status_code == 200:
            results = r.json().get("results", [])
            hits = [chunk_matches(r["content"], golden) for r in results]
            for k in [3,5,10]: vector_hits[k].append(int(any(hits[:k])))
            rr = 0.0
            for idx,h in enumerate(hits):
                if h: rr = 1.0/(idx+1); break
            vector_rr.append(rr)
    except Exception as e:
        for k in [3,5,10]: vector_hits[k].append(0)
        vector_rr.append(0.0)

    # --- hybrid ---
    try:
        r = requests.post(f"{BASE_URL}/api/v1/retrieve/hybrid",
            json={"query": zh_q, "kbIds": [kb_id], "topK": TOP_K}, timeout=30)
        if r.status_code == 200:
            results = r.json().get("results", [])
            hits = [chunk_matches(r["content"], golden) for r in results]
            for k in [3,5,10]: hybrid_hits[k].append(int(any(hits[:k])))
            rr = 0.0
            for idx,h in enumerate(hits):
                if h: rr = 1.0/(idx+1); break
            hybrid_rr.append(rr)
    except Exception as e:
        for k in [3,5,10]: hybrid_hits[k].append(0)
        hybrid_rr.append(0.0)

    # --- ES only (BM25 中文 vs 英文 chunk) ---
    try:
        r = requests.post(f"{ES_URL}/{ES_INDEX}/_search",
            headers={"Content-Type": "application/json"},
            json={"query": {"bool": {"must": [
                {"match": {"content": zh_q}},
                {"term": {"kb_id": kb_id}}
            ]}}, "size": TOP_K, "_source": ["id", "content"]}, timeout=10)
        if r.status_code == 200:
            results = [{"content": h["_source"]["content"]} for h in r.json()["hits"]["hits"]]
            hits = [chunk_matches(r["content"], golden) for r in results]
            for k in [3,5,10]: es_hits[k].append(int(any(hits[:k])))
            rr = 0.0
            for idx,h in enumerate(hits):
                if h: rr = 1.0/(idx+1); break
            es_rr.append(rr)
    except:
        for k in [3,5,10]: es_hits[k].append(0)
        es_rr.append(0.0)

    if (i+1) % 5 == 0:
        print(f"  进度: {i+1}/{len(items)}")

N = len(items)

# ─────────────────────────────────────────────────────────────────────────────
# 汇总
# ─────────────────────────────────────────────────────────────────────────────
print("\n" + "=" * 60)
print("中文 Query 跨语言检索测试结果")
print("=" * 60)
print(f"  查询语言:     中文（由 LLM 翻译）")
print(f"  知识库语言:   英文（PubMedQA chunks）")
print(f"  测试规模:     {N} 条 PubMedQA")
print(f"  Milvus chunks: {len(milvus_chunks)}")
print(f"  ES chunks:     {es_count}")
print()
print(f"  【质量 Hit@K — 中文 query 召回英文 chunks】")
print(f"  {'模式':<12} {'Hit@3':>8} {'Hit@5':>8} {'Hit@10':>8} {'MRR':>8}")
print(f"  {'-'*12} {'-'*8} {'-'*8} {'-'*8} {'-'*8}")

def fmt(hits, rr):
    return f"{sum(hits[3])/N*100:7.1f}% {sum(hits[5])/N*100:7.1f}% {sum(hits[10])/N*100:7.1f}% {sum(rr)/N*100:7.1f}%"

print(f"  {'vector':<12} {fmt(vector_hits, vector_rr)}")
print(f"  {'hybrid':<12} {fmt(hybrid_hits, hybrid_rr)}")
print(f"  {'ES-only':<12} {fmt(es_hits, es_rr)}")
print()
print("  说明: vector=纯向量检索, hybrid=向量+ES+RRF+重排, ES-only=纯BM25")
print("=" * 60)
