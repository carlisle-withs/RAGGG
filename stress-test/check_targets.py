import requests

# Prometheus API - 查看 targets
try:
    r = requests.get("http://localhost:49090/api/v1/targets", timeout=5)
    data = r.json()
    if data.get("status") == "success":
        targets = data.get("data", {}).get("activeTargets", [])
        print(f"Active targets ({len(targets)}):")
        for t in targets:
            print(f"  {t['labels']['job']} - {t['labels']['instance']} - health={t['health']} lastError={t.get('lastError', '')}")
    else:
        print(f"Error: {data}")
except Exception as e:
    print(f"无法连接 Prometheus: {e}")
