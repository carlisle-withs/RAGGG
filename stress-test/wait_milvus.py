import time, requests

# 等待 Milvus 就绪
for i in range(30):
    try:
        from pymilvus import connections, Collection
        connections.connect(host="localhost", port="29530", alias="default", timeout=5)
        c = Collection("rag_chunks")
        c.load()
        chunks = c.query(expr='kb_id == "25"', output_fields=["chunk_id"], limit=1)
        print(f"Milvus 就绪! id=25 chunks={len(chunks)}")
        c.release()
        break
    except Exception as e:
        print(f"Milvus 未就绪 ({i+1}s): {str(e)[:50]}")
        time.sleep(2)
else:
    print("Milvus 超时")

# 确认服务正常
try:
    r = requests.get("http://localhost:8081/actuator/health", timeout=5)
    print(f"Backend health: {r.status_code}")
except Exception as e:
    print(f"Backend 不可达: {e}")
