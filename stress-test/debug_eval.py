"""调试 PubMedQA golden chunk ID 映射"""
import json

DATA_DIR = "D:/Workspace/RAGGG/stress-test/data"
with open(f"{DATA_DIR}/pubmedqa_golden.jsonl") as f:
    records = [json.loads(line) for line in f]

rec = records[0]
print("Question:", rec["question"][:80])
print()
print("Golden IDs:", rec.get("relevant_chunk_ids", []))
print()

# 看第一条 records 的 documents_sentences 结构
if "documents_sentences" in rec:
    ds = rec["documents_sentences"]
    print(f"documents_sentences count: {len(ds)}")
    # 前两个文档
    for i, doc in enumerate(ds[:2]):
        print(f"\n  doc[{i}] sentences count: {len(doc)}")
        for j, sent in enumerate(doc[:3]):
            key = f"doc{i}_{j}{'a' if j < 3 else ''}"
            print(f"    [{j}] (key={key}): {sent[:80]}...")
else:
    print("No documents_sentences field")
    print("Available keys:", list(rec.keys()))

print()
# 看 chunk 结构
print("Chunks count:", len(rec["chunks"]))
print("First 3 chunks:")
for c in rec["chunks"][:3]:
    print(f"  {c['id']}: {c['text'][:80]}...")
