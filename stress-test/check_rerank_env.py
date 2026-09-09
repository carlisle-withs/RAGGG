import json, requests

BASE = "http://localhost:8081"
API = "/api/v1"

# 1. 登录
r = requests.post(f"{BASE}{API}/auth/login",
                  json={"username": "admin", "password": "admin"},
                  headers={"Content-Type": "application/json"}, timeout=10)
token = r.json().get("token")
print("1. 登录:", "OK" if token else "FAIL")

# 2. 查 OpenAPI 是否有 cross-encoder / rerank 接口
r2 = requests.get(f"{BASE}/v3/api-docs", timeout=10)
docs = r2.json() if r2.status_code == 200 else {}
paths = docs.get("paths", {})
rerank_paths = [p for p in paths if "rerank" in p.lower() or "cross" in p.lower() or "rrf" in p.lower()]
print(f"\n2. Rerank 相关接口: {rerank_paths}")
for p in rerank_paths:
    print(f"   {p}: {list(paths[p].keys())}")

# 3. 查 KB=13 的 chunks，看 chunk_id 是否一致（ES vs Milvus）
from pymilvus import connections, Collection
connections.connect(host="localhost", port="29530", alias="default")
c = Collection("rag_chunks")
c.load()
# 取几条 chunk 看结构
sample_chunks = c.query(expr='kb_id == "13"', output_fields=["chunk_id", "content", "doc_id"], limit=5)
print(f"\n3. Milvus chunks (KB=13, 前5条):")
for ch in sample_chunks:
    print(f"   chunk_id={ch['chunk_id']} doc_id={ch.get('doc_id')} content[:50]={ch.get('content','')[:50]}")

# 4. 查 ES 中相同 KB 的 chunks
import subprocess
result = subprocess.run(
    ["curl", "-s", "http://localhost:9200/rag_chunks/_search?size=5",
     "-H", "Content-Type: application/json",
     "-d", '{"query":{"term":{"kb_id":"13"}}}' ],
    capture_output=True, text=True, shell=True
)
print(f"\n4. ES chunks (KB=13):")
try:
    es_data = json.loads(result.stdout)
    hits = es_data.get("hits", {}).get("hits", [])
    for h in hits:
        src = h.get("_source", {})
        print(f"   _id={h['_id']} chunk_id={src.get('chunk_id')} doc_id={src.get('doc_id')}")
except:
    print(f"   ES 查询失败: {result.stdout[:200]}")

# 5. 查 /retrieve 接口返回的字段（看有没有 doc_id / chunk_id）
with open(r'D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
q1 = data['questanswer_1doc'][0]['questions']
body = json.dumps({"query": q1, "kbIds": ["13"], "topK": 3}).encode()
r3 = requests.post(f"{BASE}{API}/retrieve", data=body, timeout=30,
                   headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
results = r3.json().get("results", [])
print(f"\n5. /retrieve 返回字段:")
if results:
    print(f"   keys: {list(results[0].keys())}")
    for i, r_ in enumerate(results[:3]):
        print(f"   [{i}] score={r_.get('score')} id={r_.get('id')} docId={r_.get('docId')} chunkId={r_.get('chunkId')} content[:60]={r_.get('content','')[:60]}")
else:
    print(f"   无结果: {r3.status_code} {r3.text[:200]}")

c.release()