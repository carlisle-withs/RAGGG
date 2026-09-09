#!/usr/bin/env python3
"""
用 LLM 把 PubMedQA chunks 翻译成中文，构建中文测试集

流程：
  1. 加载本地 pubmedqa_golden.jsonl
  2. 对每条的 chunks 做翻译（英->中）
  3. 生成中文问题（从原问题翻译）
  4. 保存为中文测试集 JSONL
  5. 上传到知识库（中文 chunks）
  6. 用中文问题测试三种检索模式
"""
import os
import json, requests, time
from pathlib import Path

SF_API_KEY = os.environ.get("SF_API_KEY", "")
LOCAL_FILE = "data/pubmedqa_golden.jsonl"
OUTPUT_FILE = "data/pubmedqa_chinese.jsonl"
LLM_MODEL = "Qwen/Qwen2.5-7B-Instruct"

def llm_translate(text: str, target_lang="Chinese") -> str:
    """把英文翻译成中文"""
    prompt = f"Translate the following English medical research text into natural Chinese. Keep medical terminology accurate but expression natural in Chinese:\n\n{text[:2000]}"
    try:
        resp = requests.post(
            "https://api.siliconflow.cn/v1/chat/completions",
            headers={"Authorization": f"Bearer {SF_API_KEY}", "Content-Type": "application/json"},
            json={"model": LLM_MODEL, "messages": [{"role": "user", "content": prompt}], "temperature": 0.3, "max_tokens": 500},
            timeout=30
        )
        if resp.status_code == 200:
            return resp.json()["choices"][0]["message"]["content"].strip()
        else:
            return text
    except Exception as e:
        print(f"  翻译失败: {e}")
        return text

def llm_translate_question(question: str) -> str:
    """把英文问题翻译成中文（口语化）"""
    prompt = f"""将以下医学研究问题翻译成自然流畅的中文口语化表达。
不要直译，要意译，保持医学含义但表达更口语自然。

英文问题: {question}

中文口语化问题:"""
    try:
        resp = requests.post(
            "https://api.siliconflow.cn/v1/chat/completions",
            headers={"Authorization": f"Bearer {SF_API_KEY}", "Content-Type": "application/json"},
            json={"model": LLM_MODEL, "messages": [{"role": "user", "content": prompt}], "temperature": 0.5, "max_tokens": 200},
            timeout=30
        )
        if resp.status_code == 200:
            return resp.json()["choices"][0]["message"]["content"].strip()
        else:
            return question
    except Exception as e:
        print(f"  翻译失败: {e}")
        return question

# Step 1: 加载数据
print("=" * 60)
print("Step 1: 加载 PubMedQA 并翻译为中文")
print("=" * 60)

with open(LOCAL_FILE, encoding="utf-8") as f:
    raw_items = [json.loads(line) for line in f]

MAX_ITEMS = 20  # 翻译 20 条（控制 token 消耗）
chinese_items = []

for i, raw in enumerate(raw_items[:MAX_ITEMS]):
    item = raw["raw_item"]
    original_q = item["question"]

    print(f"\n[{i+1}/{MAX_ITEMS}] 翻译中...")
    print(f"  原文问题: {original_q[:60]}...")

    # 翻译问题
    chinese_q = llm_translate_question(original_q)
    print(f"  中文问题: {chinese_q[:60]}...")

    # 翻译 chunks
    chinese_chunks = []
    for chunk in item["chunks"][:30]:  # 最多30个chunk
        translated = llm_translate(chunk["text"])
        chinese_chunks.append({"id": chunk["id"], "text": translated, "orig_text": chunk["text"]})
        time.sleep(0.3)  # 避免限速

    chinese_items.append({
        "question": original_q,
        "chinese_question": chinese_q,
        "chunks": chinese_chunks,
        "relevant_chunk_ids": item.get("relevant_chunk_ids", []),
        "raw_item": item
    })

    if (i + 1) % 5 == 0:
        print(f"\n  进度: {i+1}/{MAX_ITEMS}")

print(f"\n翻译完成: {len(chinese_items)} 条")

# 保存中文测试集
with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
    for item in chinese_items:
        f.write(json.dumps(item, ensure_ascii=False) + "\n")
print(f"已保存: {OUTPUT_FILE}")
