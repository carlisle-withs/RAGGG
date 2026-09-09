from difflib import SequenceMatcher

answer = "启明行动是为了防控儿童青少年的近视问题，并发布了《防控儿童青少年近视核心知识十条》。"

chunks = [
    '国家卫健委要求，"启明行动"开展期间，医疗机构要以儿童家长和养育人为重点，结合眼保健和眼科临床服务，开展个性化咨询指导； 要针对儿童常见眼病和近视防控等重点问题，通过面对面咨询指导，引导儿童家长树立近视防控意识，改变不良生活方式，加强户外活动',
    '2023-07-28 10:14:27作者：白剑峰来源：人民日报，正文：为在全社会形成重视儿童眼健康的良好氛围，持续推进综合防控儿童青少年近视工作落实，国家卫生健康委决定在全国持续开展"启明行动"——防控儿童青少年近视健康促进活动，并发布了《防控儿童青少年近视核心知识十条》',
    '"启明行动"将开展社会宣传和健康教育，充分利用网络、广播电视、报刊杂志、海报墙报、培训讲座等多种形式，向社会公众传播开展儿童眼保健、保护儿童视力健康的重要意义，并以《防控儿童青少年近视核心知识十条》为重点普及预防近视科学知识',
    '强调预防为主，推动关口前移，倡导和推动家庭及全社会共同行动起来，营造爱眼护眼的视觉友好环境，共同呵护好孩子的眼睛，让他们拥有一个光明的未来。国家卫生健康委要求，开展社会宣传和健康教育',
]

print("Answer:", answer)
print()

for i, chunk in enumerate(chunks):
    sim = SequenceMatcher(None, chunk.lower(), answer.lower()).ratio()
    matched = sim >= 0.40
    print(f"[{i}] sim={sim:.4f} matched={matched}")
    print(f"    chunk[:80]={chunk[:80]}")

# Check if the answer text is a substring of the chunks
print("\n--- Substring check ---")
for i, chunk in enumerate(chunks):
    if answer[:30] in chunk:
        print(f"[{i}] Answer substring FOUND in chunk")
    if any(kw in chunk for kw in ['儿童青少年', '近视', '启明行动', '核心知识十条']):
        print(f"[{i}] Contains key keywords")
    # Partial keyword match
    keywords = ['启明行动', '儿童青少年', '近视', '核心知识十条']
    matched_kw = [kw for kw in keywords if kw in chunk]
    print(f"[{i}] Matched keywords: {matched_kw}")