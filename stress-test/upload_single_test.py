"""快速测试上传"""
import requests

BASE = "http://localhost:8081"

# 先找知识库 ID
resp = requests.get(f"{BASE}/api/v1/knowledge-base")
kbs = resp.json().get("records", [])
kb = next((k for k in kbs if k["name"] == "PubMedQA全链路测试"), None)
if not kb:
    print("找不到测试知识库")
    exit(1)
kb_id = kb["id"]
print(f"知识库: {kb['name']} (id={kb_id})")

# 测试上传
content = b"(1) Can surface porous PEEK microstructure be reliably controlled?"
files = {"file": ("test.txt", content, "text/plain")}
data = {"sourceType": "file"}

resp = requests.post(
    f"{BASE}/api/v1/knowledge-base/{kb_id}/docs/upload",
    files=files, data=data, timeout=30
)
print(f"上传状态: {resp.status_code}")
print(f"响应: {resp.text[:500]}")
