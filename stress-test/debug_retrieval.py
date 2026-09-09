"""调试：查看检索结果的实际内容"""
import asyncio
import aiohttp

BASE_URL = "http://localhost:8081"

async def main():
    # 登录获取 token
    async with aiohttp.ClientSession() as session:
        async with session.post(
            f"{BASE_URL}/api/v1/auth/login",
            json={"username": "admin", "password": "admin"},
            timeout=aiohttp.ClientTimeout(total=10)
        ) as resp:
            result = await resp.json()
            token = result.get("data", {}).get("token") or result.get("token")
            print(f"Token: {token[:20]}...\n")

        # 测试几个查询，看返回的 chunks 内容
        queries = [
            "Kafka 消费组 lag 如何监控",
            "RAG 检索增强生成原理",
            "ES 全文检索原理",
        ]

        for query in queries:
            async with session.post(
                f"{BASE_URL}/api/v1/retrieve",
                json={"query": query, "kbIds": ["2"], "topK": 3},
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                timeout=aiohttp.ClientTimeout(total=30)
            ) as resp:
                data = await resp.json()
                results = data.get("results", [])
                print(f"=== 查询: {query} ===")
                print(f"返回: {len(results)} 条\n")
                for i, r in enumerate(results, 1):
                    content = r.get("content", "") or ""
                    print(f"[{i}] {content[:200]}...")
                    print(f"    score={r.get('score', 0):.4f}, chunk_id={r.get('chunkId', '')[:20]}")
                    print()
                print("-" * 60)

asyncio.run(main())
