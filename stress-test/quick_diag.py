#!/usr/bin/env python3
"""CRUD-RAG 快速诊断：上传少量文档，测试检索+评测流程"""
import json, os, time, math, requests
from difflib import SequenceMatcher

BASE = "http://localhost:8081"
API = "/api/v1"
DATA_DIR = r"D:\Workspace\RAGGG\stress-test\data\crud_rag"
KB_NAME = "CRUD-RAG-Diag"
TOP_K = 10

# ============ 工具函数 ============

def login():
    r = requests.post(f"{BASE}{API}/auth/login", json={"username": "admin", "password": "admin"},
                      headers={"Content-Type": "application/json"}, timeout=10)
    if r.status_code == 200:
        return r.json().get("token")
    print(f"登录失败 {r.status_code}: {r.text[:100]}")
    return None

def create_kb(token, name):
    body = json.dumps({"name": name, "description": "CRUD-RAG 快速诊断"})
    r = requests.post(f"{BASE}{API}/knowledge-base", data=body,
                      headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"}, timeout=15)
    return r.json() if r.status_code in (200, 201) else None

def list_kb(token):
    r = requests.get(f"{BASE}{API}/knowledge-base", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    return r.json().get("records", []) if r.status_code == 200 else []

def upload_doc(content, filename, token, kb_id):
    files = {"file": (filename, content.encode("utf-8"), "text/plain")}
    try:
        r = requests.post(f"{BASE}{API}/knowledge-base/{kb_id}/docs/upload",
                          files=files, data={"sourceType": "file"}, timeout=60)
        return r.status_code in (200, 201, 202)
    except: return False

def wait_done(token, kb_id):
    from pymilvus import connections, Collection
    print("等待文档处理...")
    start = time.time()
    last_doc = last_chunk = -1
    stable = 0
    while time.time() - start < 600:
        try:
            r = requests.get(f"{BASE}{API}/knowledge-base/{kb_id}/docs",
                              params={"current": 1, "size": 1}, timeout=10,
                              headers={"Authorization": f"Bearer {token}"})
            doc_count = r.json().get("total", 0) if r.status_code == 200 else 0
        except: doc_count = 0
        try:
            connections.connect(host="localhost", port="29530", alias="default")
            c = Collection("rag_chunks")
            c.load()
            chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
            chunk_count = len(chunks)
            c.release()
        except: chunk_count = 0
        elapsed = int(time.time() - start)
        print(f"  [{elapsed:3d}s] 文档={doc_count} chunks={chunk_count}")
        if doc_count == last_doc and chunk_count == last_chunk:
            stable += 1
            if stable >= 3 and chunk_count > 0:
                print("  处理完成!")
                return True
        else: stable = 0
        last_doc = doc_count
        last_chunk = chunk_count
        time.sleep(5)
    return False

def retrieve(query, token, kb_id, topk=TOP_K):
    body = json.dumps({"query": query, "kbIds": [str(kb_id)], "topK": topk}).encode()
    r = requests.post(f"{BASE}{API}/retrieve", data=body, timeout=30,
                      headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
    return r.json().get("results", []) if r.status_code == 200 else []

def keyword_sim(chunk_text, answer_text):
    """N-gram 关键词匹配"""
    if not answer_text or not chunk_text:
        return 0.0
    ngrams = []
    for n in [4, 3, 2]:
        for i in range(len(answer_text) - n + 1):
            ngrams.append(answer_text[i:i+n])
    seen = set()
    unique = [x for x in ngrams if not (x in seen or seen.add(x))]
    if not unique: return 0.0
    matched = sum(1 for kw in unique if kw in chunk_text)
    return matched / len(unique)

# ============ 主流程 ============

print("=" * 60)
print("CRUD-RAG 快速诊断")
print("=" * 60)

# Step 1: 登录
token = login()
if not token:
    sys.exit(1)
print(f"登录成功\n")

# Step 2: 找/创建知识库
kbs = list_kb(token)
kb = next((k for k in kbs if k.get("name") == KB_NAME), None)
if not kb:
    print("创建知识库...")
    kb = create_kb(token, KB_NAME)
if not kb:
    print("知识库创建失败!")
    sys.exit(1)
kb_id = str(kb["id"])
print(f"知识库: id={kb_id}\n")

# Step 3: 加载数据（只取前 50 条）
print("Step 3: 加载数据（前 50 条/任务）...")
with open(os.path.join(DATA_DIR, "split_merged.json"), encoding="utf-8") as f:
    data = json.load(f)

# 取前 50 条 1-doc 作为测试
items_1doc = data["questanswer_1doc"][:50]
print(f"  1-doc items: {len(items_1doc)}")

# 构建 doc_map：每个 ID 只取 news1（避免太多文档）
doc_map = {}
for item in items_1doc:
    if item.get("news1") and len(item["news1"]) > 50:
        doc_key = item.get("ID", "")[:16] + "_1"
        if doc_key not in doc_map:
            doc_map[doc_key] = item["news1"]

print(f"  doc_map 大小: {len(doc_map)}\n")

# Step 4: 上传文档
print("Step 4: 上传文档...")
uploaded = errors = 0
for i, (doc_key, content) in enumerate(doc_map.items()):
    if len(content) > 10000:
        content = content[:10000]
    filename = f"doc_{i:05d}.txt"
    if upload_doc(content, filename, token, kb_id):
        uploaded += 1
    else:
        errors += 1
    if (i + 1) % 50 == 0:
        print(f"  进度: {i+1}/{len(doc_map)}")
print(f"  上传完成: {uploaded} 篇, {errors} 篇失败\n")

# Step 5: 等待处理
wait_done(token, kb_id)
print()

# Step 6: 检索+评测
print("Step 6: 检索评测（前 20 条 QA）...")
test_items = items_1doc[:20]
hit = {3: 0, 5: 0, 10: 0}
mrr_sum = 0.0

for idx, item in enumerate(test_items):
    question = item["questions"]
    answer = item["answers"]

    chunks = retrieve(question, token, kb_id, TOP_K)
    if not chunks:
        print(f"  [{idx}] 无检索结果")
        continue

    scores = []
    for c in chunks:
        sim = keyword_sim(c.get("content", ""), answer[:300])
        scores.append(sim >= 0.20)

    for k in [3, 5, 10]:
        if any(scores[:k]):
            hit[k] += 1

    rr = 0.0
    for j, s in enumerate(scores):
        if s:
            rr = 1.0 / (j + 1)
            break
    mrr_sum += rr

    # 显示每个 query 的结果
    best_pos = next((j for j, s in enumerate(scores) if s), -1)
    print(f"  [{idx:02d}] best_pos={best_pos} answer[:40]={answer[:40]}")

    # 显示 top 3 retrieval
    for ci, c in enumerate(chunks[:3]):
        content = c.get("content", "")[:60]
        score = c.get("score", 0)
        matched = scores[ci]
        print(f"      chunk[{ci}] score={score:.4f} matched={matched} text={content}")

n = len(test_items)
print(f"\n结果（n={n}）：")
print(f"  Hit@3: {hit[3]/n*100:.1f}%")
print(f"  Hit@5: {hit[5]/n*100:.1f}%")
print(f"  Hit@10: {hit[10]/n*100:.1f}%")
print(f"  MRR: {mrr_sum/n*100:.1f}%")

print("\n诊断完成!")