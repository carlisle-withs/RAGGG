"""探索 RAGBench/pubmedqa 数据结构（流式加载，避免下载全量）"""
from datasets import load_dataset

print("=" * 60)
print("  加载 pubmedqa 子数据集 (流式)... ")
print("=" * 60)

# 流式加载 test split（前 5 条）
ds = load_dataset("rungalileo/ragbench", "pubmedqa", split="test", streaming=True)
print(f"列名: {ds.column_names}")
print()

count = 0
for sample in ds:
    print(f"\n=== 第 {count+1} 条 ===")
    for key, value in sample.items():
        val_str = str(value)
        print(f"  [{key}]: {val_str[:200]}{'...' if len(val_str) > 200 else ''}")
    print()
    count += 1
    if count >= 3:
        break
