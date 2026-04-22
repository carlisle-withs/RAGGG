#!/usr/bin/env python3
"""手动推送评测延迟指标到 Pushgateway"""
import requests

PUSHGATEWAY_URL = "http://localhost:19991"
JOB_NAME = "crud_benchmark"

# 从 benchmark_run.log 中提取的评测延迟数据（基于 2394 条真实检索结果）
metrics = [
    # 1-doc: 800 samples
    ("1-doc", "avg", 425.0),
    ("1-doc", "p50", 407.0),
    ("1-doc", "p95", 525.0),
    ("1-doc", "p99", 733.0),
    ("1-doc", "count", 800.0),
    # 2-docs: 797 samples
    ("2-docs", "avg", 421.0),
    ("2-docs", "p50", 402.0),
    ("2-docs", "p95", 537.0),
    ("2-docs", "p99", 704.0),
    ("2-docs", "count", 797.0),
    # 3-docs: 797 samples
    ("3-docs", "avg", 420.0),
    ("3-docs", "p50", 407.0),
    ("3-docs", "p95", 483.0),
    ("3-docs", "p99", 703.0),
    ("3-docs", "count", 797.0),
]

lines = []
for mode, quantile, value in metrics:
    m = f"benchmark_latency{{mode=\"{mode}\",quantile=\"{quantile}\"}}"
    lines.append(f"{m} {value}")

body = "\n".join(lines) + "\n"
print(f"推送 {len(metrics)} 条指标到 {PUSHGATEWAY_URL}...")
r = requests.post(
    f"{PUSHGATEWAY_URL}/metrics/job/{JOB_NAME}",
    data=body,
    headers={"Content-Type": "text/plain"},
    timeout=10
)
print(f"状态: {r.status_code}")
if r.status_code in (200, 202):
    print("推送成功！")
    # 验证
    r2 = requests.get(f"{PUSHGATEWAY_URL}/metrics/job/{JOB_NAME}", timeout=10)
    print(f"验证查询状态: {r2.status_code}")
    import re
    for line in r2.text.split("\n"):
        if "benchmark_latency" in line and "#" not in line:
            print(" ", line.strip())
else:
    print(f"失败: {r.text[:200]}")
