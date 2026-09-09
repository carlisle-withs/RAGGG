#!/usr/bin/env python3
"""快速评测 - 只测 50 条样本，快速出结果"""
import json, time, math, requests

BASE = "http://localhost:8081"
DATA = r"D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json"
SAMPLE = 50  # 每类测多少条

# ===== 关键词提取 =====
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
    for m in re.finditer(r"\d+[年月日亿元万元套辆个件次名]", answer_text):
        kw = m.group()
        if len(kw) >= 3:
            keywords.append(kw)
    return keywords

def content_hit(chunk_content, answer_text):
    if not chunk_content or not answer_text:
        return False
    kws = extract_keywords(answer_text)
    if not kws:
        return False
    for kw in kws:
        if kw in chunk_content:
            return True
    return False

# ===== 主流程 =====
print("加载数据...")
with open(DATA, encoding="utf-8") as f:
    data = json.load(f)

# 登录
r = requests.post(f"{BASE}/api/v1/auth/login", json={"username": "admin", "password": "admin"}, timeout=10)
token = r.json().get("token", "")
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

# 找知识库
r2 = requests.get(f"{BASE}/api/v1/knowledge-base", headers=headers, timeout=10)
kb = next((k for k in r2.json().get("records", []) if k.get("name") == "CRUD-RAG-Benchmark-v2"), None)
if not kb:
    print("未找到知识库!"); exit()
kb_id = str(kb["id"])
print(f"知识库 id={kb_id}")

# 测 hybrid+rerank
total_hit3 = total_hit5 = total_mrr = total_ndcg5 = 0
total_n = 0

for key, label in [("questanswer_1doc", "1-doc"), ("questanswer_2docs", "2-docs"), ("questanswer_3docs", "3-docs")]:
    items = data[key][:SAMPLE]
    hit3 = hit5 = 0
    mrr_sum = 0.0
    ndcg5_sum = 0.0

    for item in items:
        q = item["questions"]
        a = item["answers"]

        r3 = requests.post(
            f"{BASE}/api/v1/retrieve/hybrid",
            json={"query": q, "kbIds": [kb_id], "topK": 5, "rerank": True},
            headers=headers, timeout=30
        )
        chunks = r3.json().get("results", [])
        if not chunks:
            continue

        scores = [content_hit(c.get("content", ""), a) for c in chunks]

        if any(scores[:3]): hit3 += 1
        if any(scores[:5]): hit5 += 1

        rr = 0.0
        for j, s in enumerate(scores):
            if s:
                rr = 1.0 / (j + 1)
                break
        mrr_sum += rr

        k_scores = scores[:5]
        dcg = sum((1 if k_scores[i] else 0) / math.log2(i + 2) for i in range(len(k_scores)))
        hc = sum(k_scores)
        idcg = sum(1 / math.log2(i + 2) for i in range(hc)) if hc > 0 else 0
        ndcg5_sum += dcg / idcg if idcg > 0 else 0.0

        total_n += 1

    n = len(items)
    print(f"\n{label}: Hit@3={hit3/n*100:.1f}% Hit@5={hit5/n*100:.1f}% MRR={mrr_sum/n*100:.1f}% NDCG@5={ndcg5_sum/n*100:.1f}% (n={n})")
    total_hit3 += hit3; total_hit5 += hit5; total_mrr += mrr_sum; total_ndcg5 += ndcg5_sum

total_items = SAMPLE * 3
print(f"\n平均: Hit@3={total_hit3/total_items*100:.1f}% Hit@5={total_hit5/total_items*100:.1f}% MRR={total_mrr/total_items*100:.1f}% NDCG@5={total_ndcg5/total_items*100:.1f}%")
