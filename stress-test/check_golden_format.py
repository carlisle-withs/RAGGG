import json
with open(r'D:\Workspace\RAGGG\stress-test\data\pubmedqa_golden.jsonl') as f:
    line = f.readline()
item = json.loads(line)

# chunks 详细
chunks = item.get('chunks', [])
print(f'chunks count: {len(chunks)}')
for i, c in enumerate(chunks[:5]):
    print(f'  [{i}] id={c.get("id")} text_len={len(c.get("text",""))} text={str(c.get("text",""))[:80]}')

# sentence_support_information 详细
raw = item.get('raw_item', {})
ssi = raw.get('sentence_support_information', [])
print(f'\nssi count: {len(ssi)}')
for s in ssi[:2]:
    print(f'  keys: {list(s.keys())}')
    print(f'  response_sentence_key: {s.get("response_sentence_key")}')
    print(f'  supporting_sentence_keys: {s.get("supporting_sentence_keys")}')
    print()

# documents_sentences
ds = raw.get('documents_sentences', {})
print(f'documents_sentences type: {type(ds).__name__}')
if isinstance(ds, dict):
    print(f'keys: {list(ds.keys())}')
    for k, v in list(ds.items())[:2]:
        print(f'  [{k}]: {str(v)[:100]}')
elif isinstance(ds, list):
    print(f'count: {len(ds)}')
