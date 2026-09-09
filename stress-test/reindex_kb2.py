"""
查询 MySQL 中的 document 表，找到 kb_id=2 的文档，
构造并打印 Kafka 消息，供手动发送到 document-chunked topic
"""
import json
import pymysql

# MySQL 连接
conn = pymysql.connect(
    host='localhost',
    port=3306,
    user='root',
    password='123456',
    database='rag_db',
    charset='utf8mb4'
)
cursor = conn.cursor()

# 查询 kb_id=2 的所有文档
cursor.execute("""
    SELECT d.id, d.kb_id, d.name, d.file_type, d.status,
           k.minio_path as raw_minio_path
    FROM knowledge_documents d
    JOIN knowledge_bases k ON d.kb_id = k.id
    WHERE d.kb_id = 2 AND d.deleted = 0
""")
docs = cursor.fetchall()
print(f"知识库 2 共有 {len(docs)} 个文档:")
for doc in docs[:5]:
    print(f"  id={doc[0]}, kb_id={doc[1]}, name={doc[2]}, status={doc[4]}")

conn.close()
