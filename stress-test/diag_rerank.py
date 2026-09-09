import requests, json

BASE = "http://localhost:8081"

# 登录
r = requests.post(f"{BASE}/api/v1/auth/login", json={"username": "admin", "password": "admin"}, timeout=10)
token = r.json().get("token", "")
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

# 找知识库
r2 = requests.get(f"{BASE}/api/v1/knowledge-base", headers=headers, timeout=10)
kbs = r2.json().get("records", [])
kb = next((k for k in kbs if k.get("name") == "CRUD-RAG-Benchmark-v2"), None)
if not kb:
    print("未找到知识库")
    exit()
kb_id = str(kb["id"])
print(f"知识库 id={kb_id}")

# 加载数据
with open(r"D:\Workspace\RAGGG\stress-test\data\crud_rag\split_merged.json", encoding="utf-8") as f:
    data = json.load(f)
items = data["questanswer_1doc"][:3]

for item in items:
    q = item["questions"]
    a = item["answers"]
    news1 = item.get("news1", "")

    print(f"\n问题: {q}")
    print(f"答案: {a[:80]}...")
    print(f"关联文档前100字: {news1[:100]}...")

    r3 = requests.post(
        f"{BASE}/api/v1/retrieve/hybrid",
        json={"query": q, "kbIds": [kb_id], "topK": 5, "rerank": True},
        headers=headers, timeout=30
    )
    results = r3.json().get("results", [])
    print(f"返回 {len(results)} 条")
    for i, c in enumerate(results[:3]):
        chunk = c.get("content", "")
        print(f"  [{i+1}] relevance={c.get('relevance', 0):.4f} chunk[:80]={chunk[:80]}...")

    # 简单关键词匹配测试
    keywords = []
    for ch in a:
        if ch == '"':
            kw_start = None
            for j, c2 in enumerate(a[a.index(ch)+1:]):
                if c2 == '"':
                    kw = a[a.index(ch)+1:a.index(ch)+1+j]
                    if len(kw) >= 2:
                        keywords.append(kw)
                    break

    print(f"关键词(前5): {keywords[:5]}")
    for kw in keywords[:5]:
        found_in_doc = kw in news1
        found_in_chunks = [kw in r.get("content", "") for r in results]
        print(f"  '{kw}' 在news1={found_in_doc}, 在chunks={[f for f in found_in_chunks]}")
