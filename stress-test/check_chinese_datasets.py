#!/usr/bin/env python3
"""检查可用的中文数据集"""
from datasets import load_dataset

datasets_to_try = [
    ("wanghaofei/rag_test", "train"),
    ("csttu/Chinese_medical_CQA", None),
    ("csttu/ChineseMedQA", None),
    ("Flagify/ChineseMedicalQAPairs", None),
]

for ds_name, split in datasets_to_try:
    try:
        if split:
            ds = load_dataset(ds_name, split=split, streaming=True)
        else:
            ds = load_dataset(ds_name, split="test", streaming=True)
        item = next(iter(ds))
        print(f"OK: {ds_name}")
        print(f"  keys: {list(item.keys())}")
        q = item.get("question", "")
        if not q:
            q = str(item.get(list(item.keys())[0], ""))
        print(f"  sample: {q[:80] if q else 'N/A'}")
        has_chinese = any('\u4e00' <= c <= '\u9fff' for c in q)
        print(f"  has_chinese: {has_chinese}")
        print()
    except Exception as e:
        print(f"FAIL: {ds_name} -> {str(e)[:100]}")
        print()
