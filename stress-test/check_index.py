from pymilvus import connections, Collection
connections.connect(host='localhost', port='29530', alias='default')
c = Collection('rag_chunks')
c.load()

# 查索引信息
indexes = c.indexes
for idx in indexes:
    print('Index name:', idx.field_name)
    print('Index params:', idx.params)
    print()

# 查 partition 详情
print('Partitions:', [p.name for p in c.partitions])
print('Total entities:', c.num_entities)

# 统计 kb_id 分布
results = c.query(expr='kb_id == "3"', output_fields=['chunk_id'], limit=5)
print(f'PubMedQA (kb_id=3) chunks: {len(results)} (showing first 5)')
for r in results:
    print(' ', r)
