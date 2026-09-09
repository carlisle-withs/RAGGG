import requests

BASE = "http://localhost:8081"
r = requests.post(f"{BASE}/api/v1/auth/login", json={"username": "admin", "password": "admin"}, timeout=10)
token = r.json().get("token", "")
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

r2 = requests.get(f"{BASE}/api/v1/knowledge-base", headers=headers, timeout=10)
kbs = r2.json().get("records", [])
for k in kbs:
    if "CRUD-RAG" in k.get("name", ""):
        print(f"KB: id={k['id']} name={k['name']}")

from pymilvus import connections, Collection
connections.connect(host="localhost", port="29530", alias="default")
c = Collection("rag_chunks")
c.load()
# 查询 id=25 的 chunks
try:
    chunks = c.query(expr='kb_id == "25"', output_fields=["chunk_id"], limit=5)
    print(f"id=25 chunks: {len(chunks)}")
except Exception as e:
    print(f"id=25 查询失败: {e}")

# 也查其他 id
for kb_id in ["21", "22", "23", "24"]:
    try:
        chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=5)
        print(f"id={kb_id} chunks: {len(chunks)}")
    except:
        print(f"id={kb_id}: 0 or error")
c.release()
