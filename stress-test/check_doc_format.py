import json, os

with open(r'D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# For QuestAnswer tasks, the document text is embedded in news1/news2/news3
# Check how the docs file relates to the split file
docs_dir = r'D:\Workspace\RAGGG\stress-test\data\crud_rag\docs'

# Compare first 1-doc news1 content with doc files
item = data['questanswer_1doc'][0]
news1 = item['news1']
print("=== 1-doc news1 (first 300 chars) ===")
print(news1[:300])
print()

# Check if this exact text appears in doc files
found = False
for fname in sorted(os.listdir(docs_dir))[:2]:
    path = os.path.join(docs_dir, fname)
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    # Simple substring search in first 50 lines
    lines = content.split('\n')[:50]
    for line in lines:
        if '国家卫健委' in line or '启明行动' in line:
            print(f"  Match in {fname}: {line[:100]}")
            found = True
            break
    if found:
        break

if not found:
    print("  NOT found in doc files")

# How large are the news texts?
print("\n=== news1 size ===")
print(f"news1 length: {len(news1)} chars")

# Check 2-docs news1
item2 = data['questanswer_2docs'][0]
print(f"\n2-docs news1 length: {len(item2['news1'])}")
print(f"2-docs news2 length: {len(item2['news2'])}")

# Check 3-docs
item3 = data['questanswer_3docs'][0]
print(f"\n3-docs news1 length: {len(item3['news1'])}")
print(f"3-docs news2 length: {len(item3['news2'])}")
print(f"3-docs news3 length: {len(item3['news3'])}")

# Strategy: upload news1/news2/news3 as separate documents
# Then evaluate using content_match on answer text