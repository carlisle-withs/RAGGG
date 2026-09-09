import time, requests
from pymilvus import connections, Collection

time.sleep(15)

# 查文档状态
resp = requests.get('http://localhost:8081/api/v1/knowledge-base/docs/13138')
print('文档状态:', resp.json())

# 查 Milvus
connections.connect(host='localhost', port='29530', alias='default')
c = Collection('rag_chunks')
c.load()
results = c.query(expr='kb_id == "4"', output_fields=['chunk_id','content'], limit=10)
print(f'Milvus kb_id=4: {len(results)} 条')
for r in results[:3]:
    print(' ', r.get('chunk_id'), '-', r.get('content','')[:80])
