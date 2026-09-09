import requests

# 登录
r = requests.post("http://localhost:8081/api/v1/auth/login", json={"username": "admin", "password": "admin"}, timeout=10)
token = r.json().get("token", "")
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

# 找知识库
r2 = requests.get("http://localhost:8081/api/v1/knowledge-base", headers=headers, timeout=10)
kbs = r2.json().get("records", [])
kb = next((k for k in kbs if k.get("name") == "CRUD-RAG-Benchmark-v2"), None)
if not kb:
    print("未找到知识库")
    exit()
kb_id = str(kb["id"])
print(f"使用知识库 id={kb_id}")

# 触发 hybrid+rerank 请求
for i in range(5):
    r = requests.post(
        "http://localhost:8081/api/v1/retrieve/hybrid",
        json={"query": "特朗普的法律诉讼", "kbIds": [kb_id], "topK": 5, "rerank": True},
        headers=headers, timeout=30
    )
    print(f"请求{i+1}: status={r.status_code}, count={len(r.json().get('results', []))}")

# 检查 metrics
r3 = requests.get("http://localhost:8081/actuator/prometheus", timeout=5)
lines = r3.text.split("\n")
retrieval_lines = [l for l in lines if "retrieval_" in l]
print(f"\nRetrieval metrics ({len(retrieval_lines)} 条):")
for l in retrieval_lines[:20]:
    print(l)
