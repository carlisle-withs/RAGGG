import requests
from pymilvus import connections, Collection

BASE_URL = "http://localhost:8081"
ES_URL = "http://localhost:29201"
ES_INDEX = "rag_documents"

resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == "PubMedQA全链路测试"), None)
kb_id = str(kb["id"])
print(f"kb_id={kb_id}")

connections.connect(host="localhost", port="29530", alias="default")
c = Collection("rag_chunks")
c.load()
milvus_chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id", "content"], limit=60)
print(f"Milvus chunks: {len(milvus_chunks)}")

# 检查 ES index
import json, requests
resp = requests.get(f"{ES_URL}/{ES_INDEX}/_search?size=0", headers={"Content-Type": "application/json"})
print(f"ES index '{ES_INDEX}' total: {resp.json().get('hits', {}).get('total', {}).get('value', '?')}")

# 检查 ES 中 kb_id=4 的 chunk 数量
query = {"query": {"term": {"kb_id": kb_id}}, "size": 0}
try:
    resp = requests.post(f"{ES_URL}/{ES_INDEX}/_search", json=query, headers={"Content-Type": "application/json"})
    total = resp.json().get("hits", {}).get("total", {}).get("value", 0)
    print(f"ES chunks with kb_id={kb_id}: {total}")
except Exception as e:
    print(f"ES query error: {e}")

# 列出所有文档的 doc_id
doc_ids = set()
for chunk in milvus_chunks:
    cid = chunk["chunk_id"]
    # chunk_id 格式应该是 doc{something} 或者是 UUID
    print(f"  {cid[:40]:<42} | {chunk['content'][:80]}")

c.release()
