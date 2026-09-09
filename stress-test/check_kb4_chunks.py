#!/usr/bin/env python3
from pymilvus import connections, Collection
connections.connect(host="localhost", port="29530", alias="default")
c = Collection("rag_chunks")
c.load()
results = c.query(expr='kb_id == "4"', output_fields=["chunk_id", "content"], limit=20)
print(f"Total kb_id=4 chunks: {len(results)}")
for r in results[:5]:
    print(f"  id={r['chunk_id']} | content={r['content'][:80]}")
c.release()
