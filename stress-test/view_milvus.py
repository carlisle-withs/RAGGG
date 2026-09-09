from pymilvus import MilvusClient
import numpy as np

client = MilvusClient(uri="http://localhost:29530")
collection_name = "rag_chunks"

# 1. 基本信息
stats = client.get_collection_stats(collection_name)
print(f"=== {collection_name} ===")
print(f"总记录数: {stats.get('row_count')}")

# 2. kb_id 分布
print("\n=== kb_id 分布 ===")
for kb in ["", "2"]:
    try:
        r = client.query(collection_name, filter=f'kb_id == "{kb}"' if kb else 'kb_id == ""',
                        output_fields=["kb_id"], limit=1)
        print(f"  kb_id='{kb}': 存在 (查询有结果)")
    except:
        print(f"  kb_id='{kb}': 不存在或查询无结果")

# 3. 查看具体数据内容（前 3 条）
print("\n=== 内容示例 (前 3 条 kb_id='2') ===")
try:
    results = client.query(
        collection_name,
        filter='kb_id == "2"',
        output_fields=["kb_id", "chunk_id", "content", "doc_id"],
        limit=3
    )
    for r in results:
        content = r.get("content", "") or ""
        print(f"\n  chunk_id: {r.get('chunk_id')}")
        print(f"  kb_id:    {r.get('kb_id')}")
        print(f"  doc_id:   {r.get('doc_id')}")
        print(f"  content:  {content[:120]}...")
except Exception as e:
    print(f"查询错误: {e}")

# 4. 向量搜索示例
print("\n=== 向量搜索示例 ===")
try:
    search_results = client.search(
        collection_name,
        data=[np.zeros(1024, dtype=np.float32)],
        filter='kb_id == "2"',
        limit=2,
        output_fields=["kb_id", "content"]
    )
    print(f"搜索返回: {len(search_results[0])} 条")
    for rank, hit in enumerate(search_results[0], 1):
        content = hit.get("entity", {}).get("content", "") or ""
        print(f"  #{rank} score={hit.get('distance'):.4f} kb_id={hit['entity'].get('kb_id')} content={content[:60]}...")
except Exception as e:
    print(f"搜索错误: {e}")
