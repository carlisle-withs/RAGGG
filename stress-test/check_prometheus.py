import urllib.request
import urllib.parse
import json

params = urllib.parse.urlencode({"query": "retrieval_requests_total"})
url = f"http://localhost:29090/api/v1/query?{params}"
with urllib.request.urlopen(url) as r:
    d = json.loads(r.read())
    print("Status:", d["status"])
    for item in d["data"]["result"]:
        print("  metric:", item["metric"])
        print("  value:", item["value"])

print()
params2 = urllib.parse.urlencode({"query": "histogram_quantile(0.95, retrieval_latency_ms_bucket)"})
url2 = f"http://localhost:29090/api/v1/query?{params2}"
with urllib.request.urlopen(url2) as r:
    d = json.loads(r.read())
    print("P95 查询:", d["status"])
    for item in d["data"]["result"]:
        print("  P95 latency:", round(float(item["value"][1]), 2), "ms")

print()
params3 = urllib.parse.urlencode({"query": "retrieval_qps"})
url3 = f"http://localhost:29090/api/v1/query?{params3}"
with urllib.request.urlopen(url3) as r:
    d = json.loads(r.read())
    print("QPS:", d["status"])
    for item in d["data"]["result"]:
        print("  QPS:", round(float(item["value"][1]), 2))
