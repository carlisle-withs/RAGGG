import json

with open(r'D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

print('Type:', type(data))
if isinstance(data, list):
    print('Count:', len(data))
    if data:
        print('First item keys:', list(data[0].keys()))
        print('First item:', json.dumps(data[0], ensure_ascii=False, indent=2)[:2000])
elif isinstance(data, dict):
    print('Keys:', list(data.keys()))
    for k, v in data.items():
        if isinstance(v, list):
            print(f'  {k}: {len(v)} items, first keys:', list(v[0].keys()) if v else 'empty')
            if v:
                print(f'  First item sample: {json.dumps(v[0], ensure_ascii=False)[:500]}')
        else:
            print(f'  {k}: {type(v).__name__}')