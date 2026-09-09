import json, re

with open(r"D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json", encoding="utf-8") as f:
    data = json.load(f)

a = data["questanswer_1doc"][0]["answers"]
print(f"答案: {a[:80]}")
print(f"引号 ord: {[ord(c) for c in a[:10] if c in '"\u201c\u201d' or ord(c) < 128]}")

# 提取关键词
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

    # 数字+单位
    for m in re.finditer(r"\d+[年月日亿元万元套辆个件次名]", answer_text):
        kw = m.group()
        if len(kw) >= 3:
            keywords.append(kw)
    return keywords

kws = extract_keywords(a)
print(f"关键词: {kws}")
