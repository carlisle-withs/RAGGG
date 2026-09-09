import requests

# 检查 Prometheus 是否能抓取 backend metrics
try:
    r = requests.get("http://localhost:49090/api/v1/query?query=retrieval_requests_total", timeout=5)
    data = r.json()
    if data.get("status") == "success" and data.get("data", {}).get("result"):
        print("Prometheus 抓取成功!")
        for item in data["data"]["result"]:
            print(f"  {item['metric']}: {item['value']}")
    else:
        print(f"Prometheus 无 retrieval 指标: {data}")
except Exception as e:
    print(f"Prometheus 不可达或无数据: {e}")
