import requests, os

# 直接测试 SiliconFlow rerank API
api_key = os.environ.get("SILICONFLOW_API_KEY", "")
print(f"环境变量 SILICONFLOW_API_KEY: {'已设置' if api_key else '未设置 (空)'}")

# 尝试从 config.yaml 读取
import re, yaml
try:
    with open(r"D:\Workspace\RAGGG\config.yaml", encoding="utf-8") as f:
        content = f.read()
    # 简单查找 api-key
    match = re.search(r'api-key:\s*["\']?([A-Za-z0-9_\-]+)["\']?', content)
    if match:
        api_key = match.group(1)
        print(f"从 config.yaml 读取到 api-key: {api_key[:10]}...")
    # 也查找 rerank 相关配置
    if "rerank" in content.lower():
        print("\nconfig.yaml 中 rerank 相关配置:")
        for line in content.split("\n"):
            if "rerank" in line.lower() or "siliconflow" in line.lower():
                print(f"  {line.strip()}")
except Exception as e:
    print(f"读取 config.yaml 失败: {e}")

# 直接调用 SiliconFlow rerank API
if api_key:
    print(f"\n直接调用 SiliconFlow /rerank API...")
    body = {
        "model": "BAAI/bge-reranker-v2-m3",
        "query": "特朗普面临的法律诉讼",
        "documents": [
            "美国前总统特朗普目前在多起法律案件中面临调查和诉讼。",
            "2024年美国总统大选正在筹备中。",
            "曼哈顿地区检察官对特朗普提起了刑事诉讼。",
        ],
        "top_n": 3
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    try:
        r = requests.post("https://api.siliconflow.cn/v1/rerank", json=body, headers=headers, timeout=30)
        print(f"状态码: {r.status_code}")
        if r.status_code == 200:
            data = r.json()
            print("返回结果:")
            for item in data.get("results", []):
                idx = item.get("index")
                score = item.get("relevance_score")
                doc_preview = body["documents"][idx][:30]
                print(f"  [{idx}] score={score:.4f} doc={doc_preview}...")
        else:
            print(f"错误: {r.text[:200]}")
    except Exception as e:
        print(f"请求失败: {e}")
else:
    print("\n没有 SiliconFlow API Key，无法测试")
