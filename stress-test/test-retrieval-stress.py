"""
双路检索 + 重排 全链路压测脚本

测试场景:
1. Milvus 向量检索延迟基线
2. ES BM25 检索延迟基线
3. 混合检索 (RRF 融合) 端到端延迟
4. Bi-Encoder 重排延迟 + Embedding API 调用分析
5. 并发压测: QPS 上限摸底

关键指标:
- P50/P95/P99 延迟 (端到端、各子阶段)
- QPS (不同并发下)
- Embedding API 调用次数与延迟
- 重排后 Recall 变化 (如果有标准答案)
"""

import asyncio
import aiohttp
import time
import json
import random
import sys
import os
import statistics
import threading
from dataclasses import dataclass, field
from typing import Optional
from collections import defaultdict

try:
    from prometheus_client import CollectorRegistry, Gauge, Histogram, Counter, push_to_gateway
    PROMETHEUS_ENABLED = True
except ImportError:
    PROMETHEUS_ENABLED = False

# ========== 配置 ==========
CONFIG = {
    "base_url": "http://localhost:8081",
    "api_prefix": "/api/v1",

    # 认证
    "username": "admin",
    "password": "admin",

    # 压测参数
    "concurrency": 20,
    "total_requests": 100,
    "warmup_requests": 5,        # 预热请求数 (不计入结果)

    # 检索参数
    "kb_id": "2",
    "default_topk": 10,

    # 查询池 (模拟真实查询, 覆盖短/中/长)
    "query_pool": [
        "Kafka 消费组 lag 如何监控",
        "Elasticsearch 和 MySQL 的区别",
        "Flink 日志采集方案",
        "ES|QL 和 DSL 的区别",
        "Kafka Topic 规划策略",
        "分布式系统一致性",
        "高并发场景下的限流策略",
        "什么是 RAG",
        "分布式队列限流实现",
        "日志收集中 Kafka 作用",
        "ES 全文检索原理",
        "Redis 缓存过期策略",
        "向量数据库 Milvus 使用",
        "多轮对话上下文处理",
        "语义相似度计算方法",
    ],

    "report_interval": 10,

    # Prometheus Pushgateway 配置
    "prometheus": {
        "enabled": True,
        "pushgateway_url": "http://localhost:9091",  # pushgateway 端口（映射到 29091）
        "job_name": "rag_retrieval_stress",
        "instance": "localhost",
        "namespace": "rag",
    },
}


# ========== Prometheus 指标 ==========

class PrometheusMetrics:
    def __init__(self, cfg: dict):
        self.cfg = cfg["prometheus"]
        self.enabled = PROMETHEUS_ENABLED and self.cfg["enabled"]
        self.registry = CollectorRegistry()
        if self.enabled:
            ns = self.cfg["namespace"]
            self.latency = Histogram(
                "retrieval_latency_ms", "端到端检索延迟 (ms)",
                ["phase", "kb_id"],
                buckets=[50, 75, 100, 150, 200, 300, 500, 750, 1000, 2000],
                registry=self.registry
            )
            self.success = Counter(
                "retrieval_requests_total", "总请求数 (成功)",
                ["kb_id"],
                registry=self.registry
            )
            self.failure = Counter(
                "retrieval_errors_total", "总请求数 (失败)",
                ["kb_id", "error_type"],
                registry=self.registry
            )
            self.result_count = Gauge(
                "retrieval_result_count", "检索返回结果数",
                ["kb_id"],
                registry=self.registry
            )
            self.qps = Gauge(
                "retrieval_qps", "当前 QPS",
                ["kb_id"],
                registry=self.registry
            )
            self.active_workers = Gauge(
                "retrieval_active_workers", "活跃并发 worker 数",
                ["kb_id"],
                registry=self.registry
            )

    def record(self, latency_ms: float, success: bool, result_count: int,
               error_type: Optional[str] = None, kb_id: str = "2"):
        if not self.enabled:
            return
        phase = "stress_test"
        self.latency.labels(phase=phase, kb_id=kb_id).observe(latency_ms)
        if success:
            self.success.labels(kb_id=kb_id).inc()
        else:
            self.failure.labels(kb_id=kb_id, error_type=error_type or "unknown").inc()
        self.result_count.labels(kb_id=kb_id).set(result_count)

    def set_qps(self, qps: float, kb_id: str = "2"):
        if self.enabled:
            self.qps.labels(kb_id=kb_id).set(qps)

    def set_active_workers(self, count: int, kb_id: str = "2"):
        if self.enabled:
            self.active_workers.labels(kb_id=kb_id).set(count)

    def push(self):
        if not self.enabled:
            return
        try:
            push_to_gateway(
                self.cfg["pushgateway_url"],
                job=self.cfg["job_name"],
                registry=self.registry
            )
        except Exception as e:
            print(f"  ⚠️  Prometheus push 失败: {e}")


# 全局指标实例（在 main 里初始化）
prom_metrics: Optional[PrometheusMetrics] = None


@dataclass
class RetrievalResult:
    query: str
    latency_ms: float
    result_count: int
    success: bool
    error: Optional[str] = None
    embedding_latency_ms: Optional[float] = None
    rerank_latency_ms: Optional[float] = None


@dataclass
class StressReport:
    total: int = 0
    successful: int = 0
    failed: int = 0
    latencies: list = field(default_factory=list)
    start_time: float = 0
    end_time: float = 0

    def add(self, r: RetrievalResult):
        self.total += 1
        if r.success:
            self.successful += 1
            self.latencies.append(r.latency_ms)
        else:
            self.failed += 1

    def pct(self, p: float) -> float:
        if not self.latencies:
            return 0
        s = sorted(self.latencies)
        idx = int(len(s) * p / 100)
        return round(s[min(idx, len(s) - 1)], 1)

    def print_interim(self, force=False):
        n = self.total
        if n == 0:
            return
        if not force and n % CONFIG["report_interval"] != 0:
            return

        elapsed = time.time() - self.start_time
        qps = n / elapsed if elapsed > 0 else 0

        # 推 QPS 到 Prometheus
        if prom_metrics:
            prom_metrics.set_qps(qps, kb_id=CONFIG["kb_id"])
            prom_metrics.push()

        print("\n" + "=" * 60)
        print("  📊 检索压测报告")
        print("=" * 60)
        print(f"  总请求:    {n}  |  成功: {self.successful}  |  失败: {self.failed}")
        print(f"  运行时间:  {elapsed:.1f}s  |  QPS: {qps:.2f}/s")
        print()
        print(f"  {'指标':<20} {'端到端延迟':>15}")
        print(f"  {'-'*35}")
        print(f"  {'P50 (ms)':<20} {self.pct(50):>15}")
        print(f"  {'P95 (ms)':<20} {self.pct(95):>15}")
        print(f"  {'P99 (ms)':<20} {self.pct(99):>15}")
        print(f"  {'Max (ms)':<20} {max(self.latencies, default=0):>15}")
        print(f"  {'Avg (ms)':<20} {round(statistics.mean(self.latencies), 1) if self.latencies else 0:>15}")
        print("=" * 60)

    def final_report(self):
        elapsed = self.end_time - self.start_time
        qps = self.total / elapsed if elapsed > 0 else 0

        print("\n" + "=" * 60)
        print("  🏁 检索压测最终报告")
        print("=" * 60)
        print(f"  总请求数:      {self.total}")
        print(f"  成功:          {self.successful} ({self.successful/self.total*100:.1f}%)")
        print(f"  失败:          {self.failed} ({self.failed/self.total*100:.1f}%)")
        print(f"  总耗时:        {elapsed:.1f}s")
        print(f"  吞吐量:        {qps:.2f} req/s")
        print()
        print(f"  端到端延迟 (Embedding + Milvus + ES + RRF + Rerank):")
        print(f"    P50: {self.pct(50):.1f} ms  |  P95: {self.pct(95):.1f} ms  |  P99: {self.pct(99):.1f} ms  |  Max: {max(self.latencies, default=0):.1f} ms")
        print(f"    平均: {round(statistics.mean(self.latencies), 1) if self.latencies else 0:.1f} ms")

        if self.latencies:
            p50 = self.pct(50)
            p95 = self.pct(95)
            p99 = self.pct(99)
            print()
            print("  📋 性能评估:")
            if p99 < 500:
                print("    ✅ P99 < 500ms, 性能优秀")
            elif p99 < 1000:
                print("    ⚠️  P99 500~1000ms, 性能良好，可接受")
            elif p99 < 2000:
                print("    🔴 P99 1~2s, 性能一般，可能影响用户体验")
            else:
                print("    ❌ P99 > 2s, 性能较差，需要优化")

        print("=" * 60)


# ========== 认证 ==========

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
            return None
    except Exception:
        return None


# ========== 检索请求 ==========

async def do_retrieval(
    session: aiohttp.ClientSession,
    query: str,
    kb_id: str,
    topk: int,
    token: Optional[str]
) -> RetrievalResult:
    global prom_metrics
    url = f"{CONFIG['base_url']}{CONFIG['api_prefix']}/retrieve"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    body = {
        "query": query,
        "kbIds": [kb_id],
        "topK": topk
    }

    start = time.time()
    try:
        async with session.post(url, json=body, headers=headers,
                               timeout=aiohttp.ClientTimeout(total=30)) as resp:
            latency = (time.time() - start) * 1000

            if resp.status == 200:
                data = await resp.json()
                rc = data.get("count", 0)
                if prom_metrics:
                    prom_metrics.record(latency, True, rc, kb_id=kb_id)
                return RetrievalResult(
                    query=query,
                    latency_ms=latency,
                    result_count=rc,
                    success=True
                )
            else:
                body_text = await resp.text()
                err = f"HTTP {resp.status}: {body_text[:100]}"
                if prom_metrics:
                    prom_metrics.record(latency, False, 0, error_type=err[:50], kb_id=kb_id)
                return RetrievalResult(
                    query=query,
                    latency_ms=latency,
                    result_count=0,
                    success=False,
                    error=err
                )

    except asyncio.TimeoutError:
        if prom_metrics:
            prom_metrics.record(30000, False, 0, error_type="timeout", kb_id=kb_id)
        return RetrievalResult(
            query=query, latency_ms=30000, result_count=0,
            success=False, error="Timeout > 30s"
        )
    except Exception as e:
        elapsed_ms = (time.time() - start) * 1000
        if prom_metrics:
            prom_metrics.record(elapsed_ms, False, 0, error_type="exception", kb_id=kb_id)
        return RetrievalResult(
            query=query,
            latency_ms=elapsed_ms,
            result_count=0,
            success=False,
            error=str(e)
        )


# ========== 并发压测 ==========

async def worker(
    worker_id: int,
    session: aiohttp.ClientSession,
    token: Optional[str],
    result_queue: asyncio.Queue,
    start_barrier: asyncio.Event
):
    """单个并发 worker"""
    await start_barrier.wait()

    req_id = 0
    while True:
        if req_id >= CONFIG["total_requests"]:
            break

        query = random.choice(CONFIG["query_pool"])
        result = await do_retrieval(session, query, CONFIG["kb_id"], CONFIG["default_topk"], token)
        await result_queue.put(result)
        req_id += 1


async def run_stress_test():
    global prom_metrics
    print("\n" + "=" * 60)
    print("  🚀 RAGGG 双路检索 + 重排 全链路压测")
    print("=" * 60)
    print(f"  知识库ID:     {CONFIG['kb_id']}")
    print(f"  并发数:       {CONFIG['concurrency']}")
    print(f"  目标请求数:   {CONFIG['total_requests']}")
    print(f"  预热请求数:   {CONFIG['warmup_requests']}")
    print(f"  查询池大小:   {len(CONFIG['query_pool'])} 条")
    print(f"  TopK:        {CONFIG['default_topk']}")
    print("=" * 60)

    connector = aiohttp.TCPConnector(
        limit=CONFIG["concurrency"] * 2,
        force_close=True
    )
    async with aiohttp.ClientSession(connector=connector) as session:
        # 认证
        print("\n🔑 获取认证 Token...")
        token = await get_token(session)
        if token:
            print(f"  Token: {token[:20]}...")
        else:
            print(f"  Token 获取失败，使用 guest 模式")

        # 连接测试
        print("\n🔍 测试后端连接...")
        try:
            async with session.post(
                f"{CONFIG['base_url']}{CONFIG['api_prefix']}/retrieve",
                headers={"Content-Type": "application/json"},
                json={"query": "test", "kbIds": [CONFIG["kb_id"]], "topK": 5},
                timeout=aiohttp.ClientTimeout(total=5)
            ) as resp:
                print(f"  后端连接正常, status={resp.status}")
        except Exception as e:
            print(f"  ❌ 后端连接失败: {e}")
            return

        # 预热
        print(f"\n🔥 预热 ({CONFIG['warmup_requests']} 次)...")
        warmup_done = 0
        for i in range(CONFIG["warmup_requests"]):
            query = random.choice(CONFIG["query_pool"])
            r = await do_retrieval(session, query, CONFIG["kb_id"], CONFIG["default_topk"], token)
            if r.success:
                warmup_done += 1
        print(f"  预热完成: {warmup_done}/{CONFIG['warmup_requests']}")

        # 启动压测
        print(f"\n⚡ 启动 {CONFIG['concurrency']} 个并发 worker...")
        report = StressReport()
        result_queue = asyncio.Queue()
        start_barrier = asyncio.Event()

        workers = [
            asyncio.create_task(worker(i, session, token, result_queue, start_barrier))
            for i in range(CONFIG["concurrency"])
        ]

        report.start_time = time.time()
        start_barrier.set()

        # 收集结果
        collected = 0
        while collected < CONFIG["total_requests"]:
            try:
                result = await asyncio.wait_for(result_queue.get(), timeout=60)
                report.add(result)
                collected += 1
                report.print_interim()
            except asyncio.TimeoutError:
                break

        report.end_time = time.time()

        for w in workers:
            if not w.done():
                w.cancel()

        report.final_report()
        if prom_metrics:
            prom_metrics.push()


# ========== 场景二: 延迟基线摸底 (单并发) ==========

async def run_baseline_test():
    global prom_metrics
    """单并发摸底: 测出无竞争下的基线延迟"""
    print("\n" + "=" * 60)
    print("  🎯 场景一: 延迟基线摸底 (单并发)")
    print("=" * 60)

    connector = aiohttp.TCPConnector(limit=10, force_close=True)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)

        print(f"\n  测试 {len(CONFIG['query_pool'])} 条不同查询...")
        results = []
        for i, query in enumerate(CONFIG["query_pool"]):
            r = await do_retrieval(session, query, CONFIG["kb_id"], CONFIG["default_topk"], token)
            results.append(r)
            symbol = "✅" if r.success else "❌"
            print(f"  [{i+1}/{len(CONFIG['query_pool'])}] {symbol} \"{query[:20]}...\" -> {r.latency_ms:.1f}ms, {r.result_count} 条结果")

        # 统计
        latencies = [r.latency_ms for r in results if r.success]
        if latencies:
            print(f"\n  延迟统计 (共 {len(latencies)} 个成功请求):")
            print(f"    Min:  {min(latencies):.1f} ms")
            print(f"    Avg:  {statistics.mean(latencies):.1f} ms")
            print(f"    P50:  {sorted(latencies)[len(latencies)//2]:.1f} ms")
            p95_idx = int(len(latencies) * 0.95)
            print(f"    P95:  {sorted(latencies)[min(p95_idx, len(latencies)-1)]:.1f} ms")
            print(f"    Max:  {max(latencies):.1f} ms")

            # 按延迟排序, 最慢的查询
            slowest = sorted(zip(results, latencies), key=lambda x: x[1], reverse=True)[:3]
            print(f"\n  最慢的 3 条查询:")
            for r, lat in slowest:
                print(f"    {lat:.1f}ms: \"{r.query[:30]}...\"")

        # 推 Prometheus 指标
        if prom_metrics:
            prom_metrics.push()


# ========== 场景三: 并发阶梯压测 ==========

async def run_concurrency_test():
    global prom_metrics
    """阶梯增加并发, 找到 QPS 拐点"""
    print("\n" + "=" * 60)
    print("  🎯 场景二: 并发阶梯压测")
    print("=" * 60)

    concurrency_levels = [1, 5, 10, 20, 30]
    results_summary = []

    for concurrency in concurrency_levels:
        print(f"\n  --- 并发={concurrency} ---")

        connector = aiohttp.TCPConnector(limit=concurrency * 2, force_close=True)
        async with aiohttp.ClientSession(connector=connector) as session:
            token = await get_token(session)

            report = StressReport()
            result_queue = asyncio.Queue()
            start_barrier = asyncio.Event()

            workers = [
                asyncio.create_task(worker(i, session, token, result_queue, start_barrier))
                for i in range(concurrency)
            ]

            report.start_time = time.time()
            start_barrier.set()

            collected = 0
            while collected < 20:  # 每个并发级别只测20个请求
                try:
                    result = await asyncio.wait_for(result_queue.get(), timeout=60)
                    report.add(result)
                    collected += 1
                except asyncio.TimeoutError:
                    break

            report.end_time = time.time()
            for w in workers:
                if not w.done():
                    w.cancel()

        elapsed = report.end_time - report.start_time
        qps = report.total / elapsed if elapsed > 0 else 0

        print(f"    请求: {report.total}  |  QPS: {qps:.2f}/s  |  P50: {report.pct(50):.1f}ms  |  P99: {report.pct(99):.1f}ms  |  失败: {report.failed}")

        results_summary.append({
            "concurrency": concurrency,
            "qps": qps,
            "p50": report.pct(50),
            "p95": report.pct(95),
            "p99": report.pct(99),
            "failed": report.failed
        })

    # 汇总
    print("\n  📊 并发阶梯汇总:")
    print(f"  {'并发':>8} {'QPS':>10} {'P50':>10} {'P95':>10} {'P99':>10} {'失败':>8}")
    print(f"  {'-'*56}")
    for r in results_summary:
        print(f"  {r['concurrency']:>8} {r['qps']:>10.2f} {r['p50']:>10.1f} {r['p95']:>10.1f} {r['p99']:>10.1f} {r['failed']:>8}")

    # 找拐点
    print("\n  📋 分析:")
    for i in range(1, len(results_summary)):
        prev = results_summary[i - 1]
        curr = results_summary[i]
        qps_ratio = curr["qps"] / prev["qps"]
        if qps_ratio < 0.8:
            print(f"  ⚠️  并发 {prev['concurrency']} -> {curr['concurrency']}: QPS 增长放缓 (x{qps_ratio:.2f}), 可能接近瓶颈")


# ========== Main ==========

def main():
    global prom_metrics
    prom_metrics = PrometheusMetrics(CONFIG)
    if prom_metrics.enabled:
        print("📈 Prometheus Pushgateway 已启用，指标将推送到: "
              + prom_metrics.cfg["pushgateway_url"])
    else:
        print("📈 Prometheus 未安装，跳过指标推送（pip install prometheus-client）")
    print()

    print("""
╔════════════════════════════════════════════════════════╗
║   RAGGG 双路检索 + 重排 压测脚本                        ║
║   覆盖: Embedding → Milvus → ES → RRF → Bi-Encoder    ║
╚════════════════════════════════════════════════════════╝

使用方式:
  python test-retrieval-stress.py                    # 场景1: 并发压测 (默认)
  python test-retrieval-stress.py baseline           # 场景2: 延迟基线摸底
  python test-retrieval-stress.py concurrency        # 场景3: 并发阶梯压测
  python test-retrieval-stress.py --concurrency=50   # 自定义并发数
    """)

    if len(sys.argv) < 2:
        # 默认: 并发压测
        asyncio.run(run_stress_test())
    elif sys.argv[1] == "baseline":
        asyncio.run(run_baseline_test())
    elif sys.argv[1] == "concurrency":
        asyncio.run(run_concurrency_test())
    else:
        # 解析命令行参数
        for arg in sys.argv[1:]:
            if arg.startswith("--concurrency="):
                CONFIG["concurrency"] = int(arg.split("=")[1])
            elif arg.startswith("--requests="):
                CONFIG["total_requests"] = int(arg.split("=")[1])
            elif arg.startswith("--url="):
                CONFIG["base_url"] = arg.split("=")[1]
            elif arg.startswith("--kb="):
                CONFIG["kb_id"] = arg.split("=")[1]
        asyncio.run(run_stress_test())


if __name__ == "__main__":
    main()