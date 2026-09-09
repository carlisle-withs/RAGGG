"""
检索质量评估脚本

方法一：Golden Dataset 评估
准备一批 (query, expected_chunk_keywords) 对，测试 topK 检索中有多少命中

方法二：LLM 评估（无需标注）
用 LLM 判断每个检索结果与 query 的相关性，返回 0-1 分
"""
import asyncio
import aiohttp
import json
import time
import statistics
from dataclasses import dataclass
from typing import Optional

CONFIG = {
    "base_url": "http://localhost:8081",
    "api_prefix": "/api/v1",
    "username": "admin",
    "password": "admin",
    "kb_id": "2",
    "topk": 10,
}

# ========== Golden Dataset（需要人工构造） ==========
# 格式：(query, expected_keywords) — 只要 chunk 内容包含任一 keyword 即为命中
GOLDEN_DATASET = [
    # Kafka 相关
    ("Kafka 消费组 lag 如何监控", ["consumer lag", "lag", "消费组", "监控", "JMX", "Kafka"]),
    ("Kafka Topic 规划策略", ["topic", "partition", "分区", "副本", "规划"]),
    ("Kafka 分区副本同步机制", ["replica", "ISR", "同步", "副本", "leader"]),
    # Elasticsearch 相关
    ("ES 全文检索原理", ["倒排索引", "inverted index", "分词", "analyzer", "BM25"]),
    ("ES 和 MySQL 的区别", ["ES", "Elasticsearch", "MySQL", "搜索引擎", "关系型"]),
    ("ES|QL 和 DSL 的区别", ["ES|QL", "DSL", "查询语言", "pipeline"]),
    # Flink 相关
    ("Flink Checkpoint 机制", ["checkpoint", "状态后端", "exactly-once", "at-least-once"]),
    ("Flink 窗口函数使用", ["window", "滚动窗口", "滑动窗口", "会话窗口"]),
    # 分布式系统
    ("分布式系统一致性协议 Raft", ["Raft", "一致性", "leader", "term", "日志复制"]),
    ("Paxos 算法原理", ["Paxos", "共识", "提案", "多数派", "learner"]),
    # RAG 相关
    ("RAG 检索增强生成原理", ["RAG", "retrieval", "增强生成", "检索", "LLM"]),
    ("向量检索和关键词检索的区别", ["向量检索", "keyword", "dense", "sparse", "embedding"]),
    # 数据库
    ("Redis 缓存过期策略", ["过期", "expire", "TTL", "lru", "淘汰策略"]),
    ("MySQL 索引最左前缀原则", ["最左前缀", "索引", "B+树", "联合索引", "leftmost"]),
]

# ========== 评估指标 ==========

@dataclass
class EvalResult:
    query: str
    hits: int          # 命中的 chunk 数
    total: int         # 总检索数
    hit_keywords: list # 命中的 keyword 列表
    latency_ms: float


async def get_token(session: aiohttp.ClientSession) -> Optional[str]:
    try:
        async with session.post(
            f"{CONFIG['base_url']}{CONFIG['api_prefix']}/auth/login",
            json={"username": CONFIG["username"], "password": CONFIG["password"]},
            timeout=aiohttp.ClientTimeout(total=10)
        ) as resp:
            if resp.status == 200:
                result = await resp.json()
                return result.get("data", {}).get("token") or result.get("token")
    except Exception:
        return None


async def retrieve(session: aiohttp.ClientSession, query: str, token: Optional[str]) -> tuple[list[dict], float]:
    """返回 (chunks, latency_ms)"""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = {"query": query, "kbIds": [CONFIG["kb_id"]], "topK": CONFIG["topk"]}
    start = time.time()
    try:
        async with session.post(
            f"{CONFIG['base_url']}{CONFIG['api_prefix']}/retrieve",
            json=body, headers=headers,
            timeout=aiohttp.ClientTimeout(total=30)
        ) as resp:
            latency = (time.time() - start) * 1000
            if resp.status == 200:
                data = await resp.json()
                return data.get("results", []), latency
    except Exception:
        pass
    return [], (time.time() - start) * 1000


def check_hit(chunks: list[dict], keywords: list[str]) -> tuple[int, list[str]]:
    """检查检索结果中是否命中 keyword"""
    hit_keywords = []
    for kw in keywords:
        for chunk in chunks:
            content = chunk.get("content", "")
            if kw.lower() in content.lower():
                hit_keywords.append(kw)
                break
    return len(set(hit_keywords)), list(set(hit_keywords))


async def run_evaluation():
    connector = aiohttp.TCPConnector(limit=10, force_close=True)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        print(f"Token: {'获取成功' if token else '失败，将使用 guest 模式'}\n")

        results = []
        hit_counts = []

        for i, (query, keywords) in enumerate(GOLDEN_DATASET):
            chunks, latency = await retrieve(session, query, token)
            hits, hit_kws = check_hit(chunks, keywords)

            # 计算 Hit@K（是否有任意一个 keyword 被命中）
            hit = 1 if hits > 0 else 0
            hit_counts.append(hit)

            results.append(EvalResult(
                query=query, hits=hits, total=len(chunks),
                hit_keywords=hit_kws, latency_ms=latency
            ))

            symbol = "✅" if hit else "❌"
            print(f"[{i+1}/{len(GOLDEN_DATASET)}] {symbol} \"{query[:25]}...\"")
            print(f"    命中 {hits}/{len(keywords)} keywords: {hit_kws}")
            print(f"    返回 {len(chunks)} 条, 延迟 {latency:.0f}ms")

        # ========== 汇总统计 ==========
        total_queries = len(results)
        hit_queries = sum(hit_counts)
        hit_rate = hit_queries / total_queries * 100

        print("\n" + "=" * 60)
        print("  📊 检索质量评估报告")
        print("=" * 60)
        print(f"  测试查询数:     {total_queries}")
        print(f"  知识库:        {CONFIG['kb_id']} (论文库)")
        print(f"  TopK:          {CONFIG['topk']}")
        print()
        print(f"  【核心指标】")
        print(f"  Hit@K:         {hit_queries}/{total_queries} = {hit_rate:.1f}%")
        print(f"  Miss@K:        {total_queries - hit_queries}/{total_queries} = {100-hit_rate:.1f}%")
        print()
        print(f"  【延迟】")
        print(f"  Avg:  {statistics.mean([r.latency_ms for r in results]):.1f} ms")
        print(f"  P95:  {sorted([r.latency_ms for r in results])[int(len(results)*0.95)]:.1f} ms")
        print()

        # 未命中的查询
        misses = [r for r in results if r.hits == 0]
        if misses:
            print(f"  【未命中查询】({len(misses)} 个)")
            for r in misses:
                print(f"    - {r.query[:40]}")
                print(f"      期望 keyword: {[kw for q, kw in GOLDEN_DATASET if q == r.query][0]}")
        else:
            print("  ✅ 所有查询均命中!")

        print()
        print("  📋 质量评级:")
        if hit_rate >= 90:
            print("    🏆 优秀 (Hit@K >= 90%)")
        elif hit_rate >= 70:
            print("    ✅ 良好 (Hit@K 70-90%)")
        elif hit_rate >= 50:
            print("    ⚠️  一般 (Hit@K 50-70%)")
        else:
            print("    🔴 较差 (Hit@K < 50%)，需优化检索策略")
        print("=" * 60)


if __name__ == "__main__":
    asyncio.run(run_evaluation())
