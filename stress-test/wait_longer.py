import time, requests

for i in range(60):
    try:
        r = requests.get("http://localhost:8081/actuator/health", timeout=3)
        if r.status_code == 200:
            print(f"服务已就绪 ({i+1}s)")
            print(r.json())
            break
    except Exception as e:
        pass
    time.sleep(1)
else:
    print("服务未就绪，超时")
