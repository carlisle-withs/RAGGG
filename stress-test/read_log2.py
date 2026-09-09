import os
log = r"D:\Workspace\RAGGG\stress-test\benchmark_output.log"
if os.path.exists(log):
    size = os.path.getsize(log)
    print(f"Size: {size} bytes")
    with open(log, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()
    print(content)
else:
    print("Log not found")
