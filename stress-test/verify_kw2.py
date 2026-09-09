import json, re

def extract_keywords(answer_text):
    keywords = []
    for left, right in [("\u201c", "\u201d"), ('"', '"')]:
        start = -1
        i = 0
        while i < len(answer_text):
            if answer_text[i] == left:
                start = i
            elif answer_text[i] == right and start >= 0:
                kw = answer_text[start + 1:i]
                if kw and len(kw) >= 2:
                    keywords.append(kw)
                start = -1
            i += 1
    for m in re.finditer(r"\d+[年月日亿元万元套辆个件次名]", answer_text):
        kw = m.group()
        if len(kw) >= 3:
            keywords.append(kw)
    return keywords

def content_hit(chunk_content, answer_text):
    if not chunk_content or not answer_text:
        return False
    keywords = extract_keywords(answer_text)
    if not keywords:
        return False
    for kw in keywords:
        if kw in chunk_content:
            return True
    return False

with open(r"D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json", encoding="utf-8") as f:
    data = json.load(f)

for item in data["questanswer_1doc"][:5]:
    a = item["answers"]
    news = item.get("news1", "")
    kws = extract_keywords(a)
    hit = content_hit(news, a)
    print(f"答案: {a[:60]}")
    print(f"关键词: {kws[:5]}")
    print(f"news中含关键词={hit}")
    print()
