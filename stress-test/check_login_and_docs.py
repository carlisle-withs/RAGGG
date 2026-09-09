import json

with open(r'D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Check all task types for questions/answers format
for task in ['questanswer_1doc', 'questanswer_2docs', 'questanswer_3docs']:
    item = data[task][0]
    print(f"\n=== {task} ===")
    q = item['questions']
    a = item['answers']
    print(f"Q type: {type(q).__name__}, len: {len(q)}, starts: {q[:50]}")
    print(f"A type: {type(a).__name__}, len: {len(a)}, starts: {a[:50]}")
    # Try to parse as JSON
    try:
        qj = json.loads(q)
        aj = json.loads(a)
        print(f"  Q is JSON array: {type(qj).__name__}, len={len(qj)}")
        print(f"  A is JSON array: {type(aj).__name__}, len={len(aj)}")
    except:
        print("  Q/A are plain strings (not JSON)")