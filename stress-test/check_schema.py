from pymilvus import connections, Collection
connections.connect(host='localhost', port='29530', alias='default')
c = Collection('rag_chunks')
c.load()

# 查几条现有记录
results = c.query(expr='kb_id == "2"', output_fields=['embedding', 'chunk_id'], limit=3)
for r in results:
    emb = r['embedding']
    print('chunk_id:', r['chunk_id'], '  embed_dim:', len(emb) if emb else 'None')

print()
print('Full schema fields:')
for f in c.schema.fields:
    extra = ''
    if hasattr(f, 'max_length') and f.max_length:
        extra = f' maxlen={f.max_length}'
    if hasattr(f, 'dimension') and f.dimension:
        extra = f' dim={f.dimension}'
    print(f'  {f.name} dtype={f.dtype}{extra}')
