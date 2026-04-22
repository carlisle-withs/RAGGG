#!/usr/bin/env python3
import requests, json, re

token = requests.post(
    "http://localhost:8081/api/v1/auth/login",
    json={"username": "admin", "password": "admin"},
    headers={"Content-Type": "application/json"}, timeout=10
).json().get("token")

with open(r"D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json", encoding="utf-8") as f:
    data = json.load(f)

item = data["questanswer_1doc"][0]
q = item["questions"]
a = item["answers"]
print("Q:", q[:100])
print("A:", a[:200])

r = requests.post(
    "http://localhost:8081/api/v1/retrieve/hybrid",
    json={"query": q, "kbIds": ["14"], "topK": 5, "rerank": True},
    headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
    timeout=30
)
chunks = r.json().get("results", [])
print(f"\n检索到 {len(chunks)} 条 chunks")

# Extract keywords from answer
def extract_keywords(text):
    keywords = []
    # Chinese curved quotes
    for left, right in [("\u201c", "\u201d"), ('"', '"')]:
        start = -1
        i = 0
        while i < len(text):
            if text[i] == left:
                start = i
            elif text[i] == right and start >= 0:
                kw = text[start + 1:i]
                if kw and len(kw) >= 2:
                    keywords.append(kw)
                start = -1
            i += 1
    # Numeric+unit
    for m in re.finditer(r"\d+[年月日亿元万元套辆个件次名]", text):
        kw = m.group()
        if len(kw) >= 3:
            keywords.append(kw)
    # N-grams
    stop = set(["的是", "是在", "和与", "以及", "对于", "为了", "可以",
        "这个", "那个", "因此", "但是", "而且", "或者", "什么", "哪些"])
    i = 0
    while i < len(text):
        if text[i] in '，。、；：？！""''（）【】《》,.:;?!()[]':
            i += 1
            continue
        j = i
        while j < len(text) and "\u4e00" <= text[j] <= "\u9fff":
            j += 1
        if j - i >= 2:
            phrase = text[i:j]
            if phrase not in stop and "的" not in phrase[:2] and "了" not in phrase[:2]:
                keywords.append(phrase)
        i = max(i + 1, j)
    seen = set()
    return [k for k in keywords if not (k in seen or seen.add(k))]

keywords = extract_keywords(a)
print(f"关键词数: {len(keywords)}")
print("前20个关键词:", keywords[:20])

print("\nChunk 内容分析:")
for j, c in enumerate(chunks[:5]):
    content = c.get("content", "")
    hit = any(kw in content for kw in keywords)
    # Show what keywords match
    matched = [kw for kw in keywords if kw in content]
    print(f"\nChunk {j}: hit={hit}")
    if matched:
        print(f"  匹配关键词: {matched[:5]}")
    print(f"  内容: {repr(content[:300])}")
