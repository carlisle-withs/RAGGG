import json

with open(r"D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json", encoding="utf-8") as f:
    data = json.load(f)

a = data["questanswer_1doc"][0]["answers"]
print(f"答案: {a[:60]}")
print(f"前10字符的编码:")
for i, c in enumerate(a[:10]):
    print(f"  [{i}] {repr(c)} ord={ord(c)} hex={hex(ord(c))}")
