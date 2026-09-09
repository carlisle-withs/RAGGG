#!/usr/bin/env python3
"""
三轮翻译模糊化检索测试

流程：
  EN原始问题
    → CH意译（语义保持，表达变化）
      → EN回译（换个说法，不对应对原始英文）
        → 用这个EN去测 vector + BM25

对比：
  1. EN原始 → EN chunks（baseline）
  2. EN改写 → EN chunks（模糊化后）

测试向量和BM25对查询语义变化的敏感程度
"""
import json, time, requests, aiohttp, asyncio
from difflib import SequenceMatcher
from pymilvus import connections, Collection

BASE_URL = "http://localhost:8081"
ES_URL = "http://localhost:29201"
SF_API_KEY = "***REMOVED***"
LOCAL_FILE = "D:\\Workspace\\RAGGG\\stress-test\\data\\pubmedqa_golden.jsonl"
KB_NAME = "PubMedQA全链路测试"
ES_INDEX = "rag_documents"
LLM_MODEL = "Qwen/Qwen2.5-7B-Instruct"
MAX_ITEMS = 20
TOP_K = 10

# ─────────────────────────────────────────────────────────────────────────────
# LLM 调用
# ─────────────────────────────────────────────────────────────────────────────
def llm(messages, temperature=0.9, max_tokens=200):
    try:
        resp = requests.post(
            "https://api.siliconflow.cn/v1/chat/completions",
            headers={"Authorization": f"Bearer {SF_API_KEY}", "Content-Type": "application/json"},
            json={"model": LLM_MODEL, "messages": messages, "temperature": temperature, "max_tokens": max_tokens},
            timeout=25
        )
        if resp.status_code == 200:
            return resp.json()["choices"][0]["message"]["content"].strip()
    except Exception as e:
        print(f"  LLM错误: {e}")
    return None

def en_to_ch_fuzzy(en_text: str) -> str:
    """EN → CH 意译（语义保持，表达变化）"""
    prompt = [
        {"role": "user", "content": f"""将以下英文医学研究问题翻译成自然流畅的中文口语化意译。
要求：
1. 意译，不要直译
2. 可以换词、换句式、换说法
3. 保留医学含义，但表达方式大幅变化
4. 不要出现精确的英文专业术语，保持口语化

原文: {en_text}

中文意译（换说法，口语化）:"""}
    ]
    return llm(prompt, temperature=0.95) or en_text

def ch_to_en_fuzzy(ch_text: str) -> str:
    """CH → EN 回译（不对应原始英文，换说法）"""
    prompt = [
        {"role": "user", "content": f"""Given the following Chinese text about medical research,
rewrite it into English using DIFFERENT wording than the original.

IMPORTANT: Do NOT use the exact terms from any specific medical study.
Use casual, paraphrased English that captures the general meaning.

Chinese: {ch_text}

Rewritten English (different wording):"""}
    ]
    return llm(prompt, temperature=0.9) or ch_text

# ─────────────────────────────────────────────────────────────────────────────
# 加载数据
# ─────────────────────────────────────────────────────────────────────────────
print("=" * 60)
print("加载 PubMedQA 数据")
print("=" * 60)

with open(LOCAL_FILE, encoding="utf-8") as f:
    raw_items = [json.loads(line) for line in f]

items = raw_items[:MAX_ITEMS]
print(f"  加载 {len(items)} 条，每条 {len(items[0]['raw_item']['documents'])} 篇文档")

def get_golden(item):
    sents = []
    raw = item["raw_item"]
    for di, ds in enumerate(raw["documents_sentences"]):
        for sid, st in ds:
            if sid in raw["all_relevant_sentence_keys"]:
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
# 确认知识库
# ─────────────────────────────────────────────────────────────────────────────
print("\n确认知识库")
print("=" * 60)
resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == KB_NAME), None)
if not kb:
    print("  未找到知识库")
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
# Step 1: 三轮翻译模糊化
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 1: 三轮翻译模糊化（EN → CH意译 → EN回译）")
print("=" * 60)

for i, item in enumerate(items):
    raw = item["raw_item"]
    en_orig = raw["question"]

    # EN → CH 意译
    ch_fuzzy = en_to_ch_fuzzy(en_orig)
    time.sleep(0.2)

    # CH → EN 回译（换说法）
    en_fuzzy = ch_to_en_fuzzy(ch_fuzzy)
    time.sleep(0.2)

    item["en_original"] = en_orig
    item["ch_fuzzy"] = ch_fuzzy
    item["en_fuzzy"] = en_fuzzy

    print(f"\n[{i+1}] 【原文】{en_orig[:55]}...")
    print(f"     【中文】{ch_fuzzy[:55]}...")
    print(f"     【回译】{en_fuzzy[:55]}...")

    if (i+1) % 5 == 0:
        print(f"\n  进度: {i+1}/{len(items)}")

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: 检索 + 评估
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nStep 2: 检索质量评估（原始 vs 模糊化）")
print("=" * 60)

def eval_mode(qs_list, mode_name):
    """
    qs_list: [("标签", query_fn), ...]
    query_fn(item) -> query string
    """
    results = {label: {3: [], 5: [], 10: []} for label, _ in qs_list}
    rr_results = {label: [] for label, _ in qs_list}

    for item in items:
        raw = item["raw_item"]
        golden = get_golden(item)

        for label, query_fn in qs_list:
            q = query_fn(item)
            try:
                # vector
                r = requests.post(f"{BASE_URL}/api/v1/retrieve",
                    json={"query": q, "kbIds": [kb_id], "topK": TOP_K}, timeout=30)
                if r.status_code == 200:
                    chunks = r.json().get("results", [])
                    hits = [chunk_matches(c["content"], golden) for c in chunks]
                    for k in [3, 5, 10]:
                        results[label][k].append(int(any(hits[:k])))
                    rr = 0.0
                    for idx, h in enumerate(hits):
                        if h: rr = 1.0/(idx+1); break
                    rr_results[label].append(rr)
                else:
                    for k in [3,5,10]: results[label][k].append(0)
                    rr_results[label].append(0.0)
            except:
                for k in [3,5,10]: results[label][k].append(0)
                rr_results[label].append(0.0)

            # ES
            try:
                r = requests.post(f"{ES_URL}/{ES_INDEX}/_search",
                    headers={"Content-Type": "application/json"},
                    json={"query": {"bool": {"must": [
                        {"match": {"content": q}},
                        {"term": {"kb_id": kb_id}}
                    ]}}, "size": TOP_K, "_source": ["id", "content"]}, timeout=10)
                if r.status_code == 200:
                    es_chunks = [{"content": h["_source"]["content"]} for h in r.json()["hits"]["hits"]]
                    hits = [chunk_matches(c["content"], golden) for c in es_chunks]
                    for k in [3, 5, 10]:
                        results[label][k].append(int(any(hits[:k])))
                    rr = 0.0
                    for idx, h in enumerate(hits):
                        if h: rr = 1.0/(idx+1); break
                    rr_results[label].append(rr)
            except:
                pass

    return results, rr_results

# 四组对比
# 原始英文 vector | 原始英文 BM25 | 模糊英文 vector | 模糊英文 BM25
qs_list = [
    ("EN原始-vector",   lambda item: item["en_original"]),
    ("EN原始-BM25",     lambda item: item["en_original"]),
    ("EN模糊-vector",   lambda item: item["en_fuzzy"]),
    ("EN模糊-BM25",    lambda item: item["en_fuzzy"]),
]

all_results = {}
all_rr = {}

for label, _ in qs_list:
    all_results[label] = {3: [], 5: [], 10: []}
    all_rr[label] = []

for i, item in enumerate(items):
    raw = item["raw_item"]
    golden = get_golden(item)

    for label, query_fn in qs_list:
        q = query_fn(item)
        is_vector = "vector" in label

        try:
            if is_vector:
                r = requests.post(f"{BASE_URL}/api/v1/retrieve",
                    json={"query": q, "kbIds": [kb_id], "topK": TOP_K}, timeout=30)
                if r.status_code == 200:
                    chunks = r.json().get("results", [])
                    hits = [chunk_matches(c["content"], golden) for c in chunks]
                else:
                    hits = []
            else:
                r = requests.post(f"{ES_URL}/{ES_INDEX}/_search",
                    headers={"Content-Type": "application/json"},
                    json={"query": {"bool": {"must": [
                        {"match": {"content": q}},
                        {"term": {"kb_id": kb_id}}
                    ]}}, "size": TOP_K, "_source": ["id", "content"]}, timeout=10)
                if r.status_code == 200:
                    hits = [chunk_matches({"content": h["_source"]["content"]}, golden) for h in r.json()["hits"]["hits"]]
                else:
                    hits = []
        except:
            hits = []

        for k in [3, 5, 10]:
            all_results[label][k].append(int(any(hits[:k])) if len(hits) >= k else 0)
        rr = 0.0
        for idx, h in enumerate(hits):
            if h: rr = 1.0/(idx+1); break
        all_rr[label].append(rr)

    if (i+1) % 5 == 0:
        print(f"  进度: {i+1}/{len(items)}")

N = len(items)

# ─────────────────────────────────────────────────────────────────────────────
# 汇总
# ─────────────────────────────────────────────────────────────────────────────
print("\n" + "=" * 60)
print("三轮翻译模糊化检索测试结果")
print("=" * 60)
print(f"  查询语言:     EN（原始 vs 模糊化）")
print(f"  知识库语言:  EN（PubMedQA chunks）")
print(f"  测试规模:    {N} 条 PubMedQA")
print()
print(f"  【Hit@K — EN原始 vs EN模糊化】")
print(f"  {'模式':<18} {'Hit@3':>8} {'Hit@5':>8} {'Hit@10':>8} {'MRR':>8}")
print(f"  {'-'*18} {'-'*8} {'-'*8} {'-'*8} {'-'*8}")

for label, _ in qs_list:
    r = all_results[label]
    mrr = all_rr[label]
    print(f"  {label:<18} {sum(r[3])/N*100:7.1f}% {sum(r[5])/N*100:7.1f}% {sum(r[10])/N*100:7.1f}% {sum(mrr)/N*100:7.1f}%")

print()
print("  说明:")
print("    EN原始:   原始英文问题")
print("    EN模糊:   经过 CH意译→EN回译 变换后的英文（换说法）")
print("    vector:  Milvus 向量检索")
print("    BM25:    ES 全文检索")
print("=" * 60)
