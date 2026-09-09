import requests

api_key = "***REMOVED***"
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
        print("精排成功! 返回结果:")
        for item in data.get("results", []):
            idx = item.get("index")
            score = item.get("relevance_score")
            doc_preview = body["documents"][idx][:30]
            print(f"  [{idx}] score={score:.4f} doc={doc_preview}...")
    else:
        print(f"错误: {r.text[:200]}")
except Exception as e:
    print(f"请求失败: {e}")
