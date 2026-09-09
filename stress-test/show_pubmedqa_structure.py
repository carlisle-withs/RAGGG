"""查看 PubMedQA 原始文档结构"""
import json
from datasets import load_dataset

ds = load_dataset("rungalileo/ragbench", "pubmedqa", split="test", streaming=True)
items = []
for i, item in enumerate(ds):
    items.append(item)
    if i >= 2:
        break

for i, item in enumerate(items):
    print("=" * 60)
    print(f"第 {i} 条")
    print("=" * 60)
    print(f"question: {item['question']}")
    print()
    print(f"documents 类型: {type(item['documents'])}, 数量: {len(item['documents'])}")
    for j, doc in enumerate(item["documents"][:2]):
        print(f"  doc[{j}] 长度: {len(doc)} 字符")
        print(f"  doc[{j}] 前200字: {doc[:200]}")
    print()
    print(f"documents_sentences 类型: {type(item['documents_sentences'])}")
    for j, sentences in enumerate(item["documents_sentences"][:2]):
        print(f"  doc[{j}] 句子数: {len(sentences)}")
        for k, (sent_id, text) in enumerate(sentences[:2]):
            print(f"    [{sent_id}] {text[:80]}...")
    print()
    print(f"all_relevant_sentence_keys: {item['all_relevant_sentence_keys']}")
    print()
