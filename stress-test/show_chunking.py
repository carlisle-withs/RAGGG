"""展示 PubMedQA 的分块逻辑"""
import json

with open("D:/Workspace/RAGGG/stress-test/data/pubmedqa_golden.jsonl") as f:
    records = [json.loads(line) for line in f]

rec = records[0]
print("=" * 60)
print("原始数据结构（第一条 PubMedQA）")
print("=" * 60)
print(f"question: {rec['question']}")
print()
print(f"chunks 数量: {len(rec['chunks'])} (每条 PubMedQA = 1个问题 + 对应文档的句子)")
print()
print("各 chunk 详情:")
for c in rec["chunks"]:
    print(f"  [{c['id']:8}] {c['text'][:90]}...")

print()
print(f"golden_ids（人工标注的相关句子）: {rec['relevant_chunk_ids']}")
print()
print(f"相关 chunk 内容:")
for cid in rec['relevant_chunk_ids']:
    chunk = next((c for c in rec["chunks"] if c["id"] == cid), None)
    if chunk:
        print(f"  [{cid}] {chunk['text'][:80]}...")
