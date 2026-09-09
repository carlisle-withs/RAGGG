import requests
from pymilvus import connections, Collection

BASE_URL = "http://localhost:8081"
KB_NAME = "PubMedQA全链路测试"

resp = requests.get(f"{BASE_URL}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
print(f"所有知识库:")
for k in kbs:
    print(f"  id={k['id']} name={k['name']} docs={k.get('documentCount','?')}")
kb = next((k for k in kbs if k["name"] == KB_NAME), None)
if not kb:
    print(f"未找到: {KB_NAME}")
else:
    print(f"\n目标 kb: id={kb['id']} name={kb['name']}")
    connections.connect(host="localhost", port="29530", alias="default")
    c = Collection("rag_chunks")
    c.load()
    milvus_chunks = c.query(expr=f'kb_id == "{kb["id"]}"', output_fields=["chunk_id"], limit=16384)
    print(f"Milvus chunks in '{KB_NAME}': {len(milvus_chunks)}")
    c.release()
