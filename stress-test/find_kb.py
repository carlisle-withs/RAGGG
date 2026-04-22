#!/usr/bin/env python3
"""快速查找有哪些知识库，及其 Milvus chunk 数量"""
import requests
from pymilvus import connections, Collection

BASE_URL = "http://localhost:8081"
API = "/api/v1"
ADMIN_USER = "admin"
ADMIN_PASS = "admin"

def login():
    r = requests.post(f"{BASE_URL}{API}/auth/login", json={"username": ADMIN_USER, "password": ADMIN_PASS}, timeout=10)
    if r.status_code == 200:
        return r.json().get("token")
    return None

def list_kb(token):
    r = requests.get(f"{BASE_URL}{API}/knowledge-base", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    return r.json().get("records", []) if r.status_code == 200 else []

def count_chunks(kb_id):
    try:
        connections.connect(host="localhost", port="29530", alias="default")
        c = Collection("rag_chunks")
        c.load()
        chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16383)
        n = len(chunks)
        c.release()
        return n
    except Exception as e:
        return f"ERROR: {e}"

def main():
    token = login()
    if not token:
        print("登录失败")
        return
    kbs = list_kb(token)
    if not kbs:
        print("没有知识库")
        return
    print(f"{'ID':>6}  {'名称':<40}  {'docs':>6}  {'chunks':>8}")
    print("-" * 70)
    for kb in kbs:
        kid = str(kb["id"])
        n_chunks = count_chunks(kid)
        print(f"{kid:>6}  {kb['name']:<40}  {kb.get('documentCount', 0):>6}  {str(n_chunks):>8}")
    connections.disconnect(alias="default")

if __name__ == "__main__":
    main()
