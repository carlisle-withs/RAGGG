from pymilvus import connections, Collection
connections.connect(host='localhost', port='29530', alias='default')
c = Collection('rag_chunks')
c.load()
results = c.query(expr='kb_id == "4"', output_fields=['chunk_id','content'], limit=5)
print(f'kb_id=4 总 chunks: {len(results)}')
for r in results[:3]:
    print(' ', r.get('chunk_id'), '-', r.get('content','')[:60])
