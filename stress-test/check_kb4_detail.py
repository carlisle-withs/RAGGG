from pymilvus import connections, Collection
connections.connect(host='localhost', port='29530', alias='default')
c = Collection('rag_chunks')
c.load()

# 查 kb_id=4 的 chunks
results = c.query(expr='kb_id == "4"', output_fields=['chunk_id', 'content'], limit=100)
print(f'kb_id=4 chunks: {len(results)}')
for r in results[:5]:
    print(f'  [{r["chunk_id"]}] {r["content"][:80]}...')

print()

# 也查 kb_id=3 的（原始 PubMedQA）
results3 = c.query(expr='kb_id == "3"', output_fields=['chunk_id'], limit=5)
print(f'kb_id=3 chunks: {len(results3)}')

# 总实体数
print(f'\n总实体数: {c.num_entities}')
