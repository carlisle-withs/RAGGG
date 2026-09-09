#!/usr/bin/env python3
"""探索 wanghaofei/rag_test 的结构和内容"""
from datasets import load_dataset

ds = load_dataset("wanghaofei/rag_test", split="train", streaming=True)
items = []
for i, item in enumerate(ds):
    items.append(item)
    if i >= 5:
        break

for i, item in enumerate(items):
    text = item.get("text", "")
    print(f"[{i}] text: {text[:150]}")
    # 检查是否包含中文
    has_chinese = any('\u4e00' <= c <= '\u9fff' for c in text)
    print(f"    has_chinese: {has_chinese}")
    print()
