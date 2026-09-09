import requests

BASE = "http://localhost:8081"

# 1. 登录
r = requests.post(f"{BASE}/api/v1/auth/login", json={"username": "admin", "password": "admin"}, timeout=10)
token = r.json().get("token", "")
print(f"登录: {r.status_code} token={token[:20]}...")

headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

# 2. 找知识库
r2 = requests.get(f"{BASE}/api/v1/knowledge-base", headers=headers, timeout=10)
kbs = r2.json().get("records", [])
names = [k["name"] for k in kbs]
print(f"知识库列表: {names}")
kb = next((k for k in kbs if k.get("name") == "CRUD-RAG-Benchmark-v2"), None)
if not kb:
    kb = next((k for k in kbs if k.get("name") == "CRUD-RAG-Benchmark"), None)
if not kb:
    print("未找到知识库!")
    exit()
kb_id = str(kb["id"])
print(f"使用知识库: {kb['name']} id={kb_id}")

# 3. 测试精排
query = "特朗普面临的法律诉讼"
topk = 5

# 不精排
r3 = requests.post(f"{BASE}/api/v1/retrieve", json={"query": query, "kbIds": [kb_id], "topK": topk, "rerank": False}, headers=headers, timeout=30)
results_no_rerank = r3.json().get("results", [])
print(f"\n不精排 (rerank=false): {len(results_no_rerank)} 条, status={r3.status_code}")
for i, r in enumerate(results_no_rerank[:3]):
    print(f"  [{i+1}] score={r.get('score', 0):.4f} chunk_id={r.get('chunkId', '')[:20]}...")

# 精排
r4 = requests.post(f"{BASE}/api/v1/retrieve", json={"query": query, "kbIds": [kb_id], "topK": topk, "rerank": True}, headers=headers, timeout=30)
print(f"\n精排 (rerank=true): status={r4.status_code}")
if r4.status_code != 200:
    print(f"  HTTP错误: {r4.text[:300]}")
else:
    results_rerank = r4.json().get("results", [])
    print(f"  返回 {len(results_rerank)} 条")
    for i, r in enumerate(results_rerank[:3]):
        print(f"  [{i+1}] score={r.get('score', 0):.4f} relevance={r.get('relevance', 0):.4f} chunk_id={r.get('chunkId', '')[:20]}...")

    # 4. 对比
    if results_no_rerank and results_rerank:
        same = all(a.get("chunkId") == b.get("chunkId") for a, b in zip(results_no_rerank, results_rerank))
        print(f"\n结论: Top5 顺序完全相同? {same}")
        if same:
            print("警告: 精排未改变结果顺序!")
