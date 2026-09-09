import requests, json

BASE = "http://localhost:8081"
kb_id = "4"

# 测试检索
payload = {
    "query": "Do Surface Porosity and Pore Size Influence Mechanical Properties and Cellular Response to PEEK?",
    "kbIds": [kb_id],
    "topK": 5
}

resp = requests.post(f"{BASE}/api/v1/retrieve", json=payload, timeout=30)
print(f"检索状态: {resp.status_code}")
print(f"响应: {json.dumps(resp.json(), indent=2, ensure_ascii=False)[:2000]}")
