import requests, json

ES_URL = "http://localhost:29201"
ES_INDEX = "rag_documents"
KB_ID = "4"

# 获取 ES 中 kb_id=4 的 chunk，查看 doc_id
query = {
    "query": {"term": {"kb_id": KB_ID}},
    "size": 20,
    "_source": ["chunk_id", "content", "doc_id", "kb_id"]
}
resp = requests.post(f"{ES_URL}/{ES_INDEX}/_search", json=query, headers={"Content-Type": "application/json"})
data = resp.json()
hits = data.get("hits", {}).get("hits", [])
print(f"ES kb_id={KB_ID} total in ES: {data.get('hits', {}).get('total', {}).get('value', '?')}")
print(f"Showing {len(hits)} chunks:\n")
for h in hits:
    src = h.get("_source", {})
    print(f"  doc_id={str(src.get('doc_id',''))[:20]:<22} chunk_id={src.get('chunk_id','')[:30]}")
    print(f"    content: {src.get('content','')[:120]}")
    print()

# 看看这些 doc_id 对应的文档
doc_ids = set(src.get("doc_id") for h in hits for src in [h.get("_source", {})] if src.get("doc_id"))
print(f"唯一 doc_id 数: {len(doc_ids)}")
print(f"doc_id 示例: {list(doc_ids)[:10]}")
