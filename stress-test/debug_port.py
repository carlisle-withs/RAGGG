import requests

try:
    r = requests.get("http://localhost:8081/actuator/health", timeout=5)
    print(f"Health: {r.status_code} {r.text[:200]}")
except Exception as e:
    print(f"Health failed: {e}")

try:
    r = requests.get("http://localhost:8081/api/v1/knowledge-base", timeout=5)
    print(f"KB API: {r.status_code}")
except Exception as e:
    print(f"KB API failed: {e}")
