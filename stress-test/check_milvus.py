import requests

# 检查知识库状态
BASE = "http://localhost:8081"
r = requests.post(f"{BASE}/api/v1/auth/login", json={"username": "admin", "password": "admin"}, timeout=10)
token = r.json().get("token", "")
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

r2 = requests.get(f"{BASE}/api/v1/knowledge-base", headers=headers, timeout=10)
kbs = r2.json().get("records", [])
kb = next((k for k in kbs if k.get("name") == "CRUD-RAG-Benchmark-v2"), None)
if not kb:
    print("未找到知识库 CRUD-RAG-Benchmark-v2")
else:
    print(f"知识库: id={kb['id']} name={kb['name']}")

# 测试 Milvus 连接
try:
    from pymilvus import connections, Collection
    connections.connect(host="localhost", port="29530", alias="default")
    c = Collection("rag_chunks")
    c.load()
    results = c.query(expr='kb_id == "21"', output_fields=["chunk_id"], limit=5)
    print(f"Milvus 查询成功: {len(results)} 条 (最多显示5条)")
    c.release()
except Exception as e:
    print(f"Milvus 连接失败: {e}")
