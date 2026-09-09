from pymilvus import connections, Collection
connections.connect(host='localhost', port='29530', alias='default')
c = Collection('rag_chunks')
c.load()
results = c.query(expr='kb_id == "3"', output_fields=['chunk_id', 'doc_id'], limit=10000)
print(f'kb_id=3 总 chunks: {len(results)}')
print('前5条:', results[:5])
