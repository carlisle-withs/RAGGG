import json

with open(r'D:\Workspace\RAGGG\stress-test\data\pubmedqa_golden.jsonl', encoding='utf-8') as f:
    item = json.loads(f.readline())

raw = item.get('raw_item', {})
print("raw_item keys:", list(raw.keys()))
print()

# documents_sentences 结构
ds = raw.get('documents_sentences', [])
print(f"documents_sentences: {len(ds)} items")
for i, doc_sents in enumerate(ds[:3]):
    print(f"  Doc[{i}]: {len(doc_sents)} sentences")
    for j, (sid, stext) in enumerate(doc_sents[:3]):
        print(f"    [{sid}]: {stext[:100]}")

# 查找是否有 contact lens 句子
print("\n--- 搜索 contact lens 相关句子 ---")
all_sents = []
for di, doc_sents in enumerate(ds):
    for sid, stext in doc_sents:
        all_sents.append((di, sid, stext))
        if 'contact lens' in stext.lower() or 'disability pension' in stext.lower():
            print(f"  Doc[{di}] sid={sid}: {stext[:120]}")

# 对比第一条 item 的 chunks
chunks = item.get('chunks', [])
print(f"\n--- Item chunks ({len(chunks)} chunks) ---")
for c in chunks[:5]:
    print(f"  [{c['id']}]: {c['text'][:100]}")

# 对比 ES 中 kb_id=4 的内容
print("\n--- kb_id=4 chunk 内容 ---")
es_sents = [
    "Women were found to have higher rates of disability pension than men, regardless of diagnosis, whereas men had a steeper",
    "The authors studied 60 patients of distinct social groups at the same Contact Lens Department at Sorocaba Eye Hospital",
    "To compare the habits of United States (US) soft contact lens (SCL) wearers",
]
for s in es_sents:
    # 搜索这条句子是否在 PubMedQA 中
    found = False
    for di, sid, stext in all_sents:
        if s[:60] in stext:
            print(f"  FOUND in Doc[{di}]{sid}: {stext[:100]}")
            found = True
    if not found:
        print(f"  NOT FOUND: {s[:100]}")
