#!/usr/bin/env python3
"""探索 wanghaofei/rag_test 的结构和内容"""
from datasets import load_dataset

ds = load_dataset("wanghaofei/rag_test", split="train", streaming=True)
items = []
for i, item in enumerate(ds):
    items.append(item)
    if i >= 20:
        break

# 看所有字段
print("=== 字段 ===")
print(list(items[0].keys()))

# 看 sources
sources = set(item.get("source", "") for item in items)
print(f"\n=== 数据集来源 ===")
print(sources)

# 看各 source 的 question 语言
for src in sources:
    sample = next(item for item in items if item.get("source") == src)
    q = sample.get("question", "")
    has_chinese = any('\u4e00' <= c <= '\u9fff' for c in q)
    print(f"\n  [{src}]")
    print(f"  question: {q[:80]}")
    print(f"  has_chinese: {has_chinese}")
    print(f"  keys: {list(sample.keys())}")
