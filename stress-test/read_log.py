import time, os

log = r"D:\Workspace\RAGGG\stress-test\benchmark_output.log"
while True:
    if os.path.exists(log):
        size = os.path.getsize(log)
        print(f"Log size: {size} bytes")
        with open(log, encoding="utf-8") as f:
            content = f.read()
        print(f"Content ({len(content)} chars):")
        print(content[-2000:] if len(content) > 2000 else content)
    else:
        print("Log file not found")
    time.sleep(30)
