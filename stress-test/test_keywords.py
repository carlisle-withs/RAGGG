import re

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

# 测试
answers = [
    '"启明行动"是为了防控儿童青少年的近视问题，并发布了《防控儿童青少年近视核心知识十条》。',
    '陕西西安市发放了500万元体育类电子消费券，市民可以在全市173家体育场馆使用这些消费券。',
    '国家药监局在对牙科低压电动马达、贴敷类医疗器械（远红外治疗贴、磁疗贴、穴位磁疗贴）、立式压力蒸汽灭菌器、电动吸引器和人体血液及血液成分袋式塑料容器（血袋）这5个品种...'
]

for a in answers:
    kws = extract_keywords(a)
    print(f"答案: {a[:60]}...")
    print(f"关键词: {kws[:8]}")
    print()
