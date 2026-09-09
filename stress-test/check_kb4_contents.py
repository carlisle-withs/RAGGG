import requests
from pymilvus import connections, Collection
import json

BASE_URL = "http://localhost:8081"
KB_NAME = "PubMedQA全链路测试"

resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == KB_NAME), None)
kb_id = str(kb["id"])
print(f"kb_id={kb_id} ({KB_NAME})")

connections.connect(host="localhost", port="29530", alias="default")
c = Collection("rag_chunks")
c.load()
chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id", "content"], limit=60)
print(f"Total chunks in Milvus: {len(chunks)}")
print()
for i, chunk in enumerate(chunks):
    print(f"[{i:2d}] {chunk['chunk_id'][:30]:<32} | {chunk['content'][:120]}")
c.release()
