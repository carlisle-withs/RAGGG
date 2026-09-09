"""
Milvus kb_id 修复脚本 - 直接删除并重建 collection
"""
from pymilvus import MilvusClient, connections, CollectionSchema, FieldSchema, Collection, DataType
import numpy as np

MILVUS_URI = "http://localhost:29530"
COLLECTION_NAME = "rag_chunks"
DIMENSION = 1024

print("=== Step 1: 删除 rag_chunks collection ===")
client = MilvusClient(uri=MILVUS_URI)
try:
    client.drop_collection(COLLECTION_NAME)
    print(f"  ✅ 已删除 collection: {COLLECTION_NAME}")
except Exception as e:
    print(f"  删除 collection 错误: {e}")

print("\n=== Step 2: 重新创建 collection (结构不变) ===")
try:
    client.create_collection(
        collection_name=COLLECTION_NAME,
        dimension=DIMENSION,
        primary_field="chunk_id",
        vector_field="embedding",
        schema={
            "fields": [
                {"name": "chunk_id", "type": DataType.VARCHAR, "params": {"max_length": 36}, "is_primary": True},
                {"name": "doc_id", "type": DataType.VARCHAR, "params": {"max_length": 36}},
                {"name": "document_id", "type": "VARCHAR", "params": {"max_length": 36}},
                {"name": "content", "type": DataType.VARCHAR, "params": {"max_length": 65535}},
                {"name": "embedding", "type": DataType.FLOAT_VECTOR, "params": {"dim": DIMENSION}},
                {"name": "kb_id", "type": DataType.VARCHAR, "params": {"max_length": 36}},
            ],
            "auto_id": False,
            "description": "RAG chunk vectors"
        },
        index_params={
            "metric_type": "COSINE",
            "index_type": "HNSW",
            "params": {"M": 16, "efConstruction": 200}
        }
    )
    print(f"  ✅ 已创建 collection: {COLLECTION_NAME}")
except Exception as e:
    print(f"  创建 collection 错误: {e}")

print("\n=== Step 3: 验证 collection 状态 ===")
try:
    coll_info = client.describe_collection(COLLECTION_NAME)
    print(f"  Collection 信息: dim={coll_info.get('dim')}")
    results = client.search(
        COLLECTION_NAME,
        data=[np.zeros(DIMENSION, dtype=np.float32)],
        limit=1,
        output_fields=["kb_id"]
    )
    print(f"  搜索结果: {len(results[0])} 条 (应该为 0)")
except Exception as e:
    print(f"  验证错误: {e}")

print("\n✅ Milvus collection 已重建完毕!")
print("现在需要重新索引文档，请重启后端后上传文档到知识库触发索引。")
