"""
文档上传全链路压测脚本 v2

测试场景:
1. 文档上传 (MinIO -> DB -> Kafka)
2. 解析流水线 (Tika -> Chunk -> Embed -> Store)
3. 文档状态轮询 (PENDING -> PARSING -> CHUNKING -> COMPLETED)

新增埋点集成:
- Prometheus 实时拉取各阶段延迟 P50/P95/P99
- Embedding / ES / Milvus / MySQL 每 chunk 操作耗时
- 每 10 秒输出一次 Prometheus 指标摘要

Grafana: http://localhost:23000 (admin / admin)
Prometheus: http://localhost:29090
"""

import asyncio
import aiohttp
import subprocess
import time
import json
import random
import sys
import os
import statistics
from dataclasses import dataclass, field
from typing import Optional
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from reportlab.lib.units import cm
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import Paragraph

# ========== 配置 ==========
# --- 测试数据源 ---
# mode: "generated"  使用脚本自动生成的 PDF（不同大小的测试文件）
# mode: "folder"    扫描真实文档文件夹上传
TEST_MODE = "folder"

# generated 模式下生成的文件配置
GENERATED_FILE_SIZES_MB = [0.5, 1.0, 2.0, 5.0, 10.0]  # MB

# folder 模式下扫描的真实文档目录
REAL_DOCS_FOLDER = os.path.join(os.path.dirname(__file__), "real-docs")
# 支持的文件类型
SUPPORTED_EXTENSIONS = {".pdf", ".docx", ".doc", ".txt", ".md", ".pptx", ".xlsx"}

CONFIG = {
    "base_url": "http://localhost:8081",
    "api_prefix": "/api/v1",
    # Prometheus 查询地址
    "prometheus_url": "http://localhost:29090",
    # 认证
    "username": "admin",
    "password": "admin",
    # 压测参数
    "concurrency": 5,            # 并发用户数
    "total_requests": 0,          # 总请求数 (0 = 循环跑完文件夹所有文件一遍)
    "duration_seconds": 0,       # 持续时间 (0 = 按 total_requests 或文件轮次)
    "poll_interval_ms": 3000,    # 状态轮询间隔
    "poll_timeout_ms": 600000,    # 状态轮询超时 (大文件解析慢，10分钟)
    "kb_id": 2,                   # 知识库 ID（论文库）
    # 分块策略: fixed / recursive / semantic
    "chunk_strategy": "fixed",
    "report_interval": 5,         # 每 N 次输出一次报告
    "prometheus_poll_seconds": 15,# Prometheus 指标轮询间隔
}


@dataclass
class UploadRequest:
    file_name: str
    file_size_mb: float
    file_path: str
    chunk_strategy: str = "fixed"


@dataclass
class UploadResult:
    file_name: str
    document_id: str
    upload_latency_ms: float
    trace_id: str
    status: str
    parse_latency_ms: Optional[float] = None
    error: Optional[str] = None


@dataclass
class StressTestReport:
    total_requests: int = 0
    successful: int = 0
    failed: int = 0
    upload_latencies: list = field(default_factory=list)
    parse_latencies: list = field(default_factory=list)
    start_time: float = 0
    end_time: float = 0

    def add_result(self, result: UploadResult):
        self.total_requests += 1
        if result.error:
            self.failed += 1
        else:
            self.successful += 1
            if result.upload_latency_ms:
                self.upload_latencies.append(result.upload_latency_ms)
            if result.parse_latency_ms:
                self.parse_latencies.append(result.parse_latency_ms)

    def print_report(self, force=False):
        n = self.total_requests
        if n == 0 or (not force and n % CONFIG["report_interval"] != 0):
            return
        if n == 0:
            return

        elapsed = time.time() - self.start_time
        qps = n / elapsed if elapsed > 0 else 0

        def pct(lst, p):
            if not lst:
                return 0
            s = sorted(lst)
            idx = int(len(s) * p / 100)
            return round(s[min(idx, len(s) - 1)], 1)

        print("\n" + "=" * 60)
        print(f"  📊 压测报告 (每 {CONFIG['report_interval']} 条输出)")
        print("=" * 60)
        print(f"  总请求:    {n}  |  成功: {self.successful}  |  失败: {self.failed}")
        print(f"  运行时间:  {elapsed:.1f}s  |  QPS: {qps:.2f}/s")
        print()
        print(f"  {'指标':<20} {'上传延迟':>15} {'解析延迟':>15}")
        print(f"  {'-'*50}")
        print(f"  {'P50 (ms)':<20} {pct(self.upload_latencies, 50):>15} {pct(self.parse_latencies, 50):>15}")
        print(f"  {'P95 (ms)':<20} {pct(self.upload_latencies, 95):>15} {pct(self.parse_latencies, 95):>15}")
        print(f"  {'P99 (ms)':<20} {pct(self.upload_latencies, 99):>15} {pct(self.parse_latencies, 99):>15}")
        print(f"  {'Max (ms)':<20} {max(self.upload_latencies, default=0):>15} {max(self.parse_latencies, default=0):>15}")
        print(f"  {'Avg (ms)':<20} {round(statistics.mean(self.upload_latencies), 1) if self.upload_latencies else 0:>15} "
              f"{round(statistics.mean(self.parse_latencies), 1) if self.parse_latencies else 0:>15}")
        print("=" * 60)

    def final_report(self, prometheus_available: bool = True):
        elapsed = self.end_time - self.start_time
        qps = self.total_requests / elapsed if elapsed > 0 else 0

        def pct(lst, p):
            if not lst:
                return "N/A"
            s = sorted(lst)
            idx = int(len(s) * p / 100)
            return f"{round(s[min(idx, len(s) - 1)], 1)} ms"

        print("\n" + "=" * 80)
        print("  🏁 最终压测报告")
        print("=" * 80)
        print(f"  总请求数:      {self.total_requests}")
        print(f"  成功:          {self.successful} ({self.successful/self.total_requests*100:.1f}%)")
        print(f"  失败:          {self.failed} ({self.failed/self.total_requests*100:.1f}%)")
        print(f"  总耗时:        {elapsed:.1f}s")
        print(f"  吞吐量:        {qps:.2f} req/s")
        print()
        print(f"  客户端上报延迟:")
        print(f"    上传  P50: {pct(self.upload_latencies, 50)}, P95: {pct(self.upload_latencies, 95)}, P99: {pct(self.upload_latencies, 99)}")
        print(f"    端到端 P50: {pct(self.parse_latencies, 50)}, P95: {pct(self.parse_latencies, 95)}, P99: {pct(self.parse_latencies, 99)}")

        if prometheus_available and self.successful >= 3:
            print()
            print(f"  📊 Prometheus 微服务埋点延迟 (服务端实测):")
            print(f"  {'阶段':<30} {'P50 (ms)':>15} {'P95 (ms)':>15}")
            print(f"  {'-'*60}")
            for stage, desc in [
                ("minio", "  MinIO 上传"),
                ("db_save", "  DB 保存"),
                ("kafka_send", "  Kafka 发送"),
                ("parse", "  解析 (Tika)"),
                ("chunk", "  分块"),
                ("index", "  索引 (全链路)"),
            ]:
                if stage in ("minio", "db_save", "kafka_send"):
                    q_p50 = f'histogram_quantile(0.50, sum(rate(doc_upload_stage_seconds_bucket{{stage="{stage}"}}[5m])) by (le))'
                    q_p95 = f'histogram_quantile(0.95, sum(rate(doc_upload_stage_seconds_bucket{{stage="{stage}"}}[5m])) by (le))'
                else:
                    q_p50 = f'histogram_quantile(0.50, sum(rate(doc_pipeline_seconds_bucket{{stage="{stage}"}}[5m])) by (le))'
                    q_p95 = f'histogram_quantile(0.95, sum(rate(doc_pipeline_seconds_bucket{{stage="{stage}"}}[5m])) by (le))'
                v50 = query_prometheus(q_p50)
                v95 = query_prometheus(q_p95)
                v50s = f"{round(v50*1000,1)}" if v50 else "N/A"
                v95s = f"{round(v95*1000,1)}" if v95 else "N/A"
                print(f"  {desc:<30} {v50s:>15} {v95s:>15}")

            print()
            print(f"  🔬 Index 每 Chunk 各操作延迟 (P95):")
            print(f"  {'操作':<30} {'P95 (ms)':>15}")
            print(f"  {'-'*45}")
            for op, desc in [
                ("embed_batch", "  Embedding (批量 LLM API)"),
                ("persist", "  持久化 (MySQL+ES+Milvus)"),
            ]:
                q = f'histogram_quantile(0.95, sum(rate(doc_pipeline_chunk_ops_seconds_bucket{{op="{op}"}}[5m])) by (le))'
                v = query_prometheus(q)
                vs = f"{round(v*1000,1)}" if v else "N/A"
                print(f"  {desc:<30} {vs:>15}")

            print()
            print(f"  📈 各阶段吞吐量:")
            for q_name, q_expr, unit in [
                ("上传 QPS", "sum(rate(doc_upload_seconds_count[5m]))", "req/s"),
                ("解析 QPS", "sum(rate(doc_pipeline_seconds_count{stage=\"parse\"}[5m]))", "doc/s"),
                ("索引 QPS", "sum(rate(doc_pipeline_seconds_count{stage=\"index\"}[5m]))", "doc/s"),
                ("Chunk 吞吐", "sum(rate(doc_pipeline_chunk_count_total{status=\"success\"}[5m]))", "chunks/s"),
            ]:
                v = query_prometheus(q_expr)
                print(f"    {q_name:<28} {f'{v:.2f}' if v else 'N/A':>12} {unit}")

            print()
            print(f"  ✅ Grafana 仪表板: http://localhost:23000  (admin / admin)")
            print(f"     仪表板名称: RAGGG 文档上传全链路仪表板")

        print("=" * 80)


# ========== 测试文件生成 ==========

def generate_test_pdfs(output_dir: str, count: int = 5):
    """生成多个不同大小的测试 PDF 文件"""
    files = []
    # 不同文件大小配置 (MB)
    sizes = [0.1, 0.3, 0.5, 1.0, 2.0, 5.0]

    os.makedirs(output_dir, exist_ok=True)

    for i in range(min(count, len(sizes))):
        size_mb = sizes[i]
        file_path = os.path.join(output_dir, f"stress_test_{int(size_mb*10)}00kb.pdf")

        if os.path.exists(file_path):
            print(f"  使用已有测试文件: {file_path}")
            files.append(UploadRequest(
                file_name=f"stress_test_{int(size_mb*10)}00kb.pdf",
                file_size_mb=size_mb,
                file_path=file_path
            ))
            continue

        print(f"  生成测试 PDF ({size_mb} MB): {file_path}")
        generate_pdf(file_path, size_mb)

        files.append(UploadRequest(
            file_name=f"stress_test_{int(size_mb*10)}00kb.pdf",
            file_size_mb=size_mb,
            file_path=file_path
        ))

    return files


def generate_pdf(file_path: str, target_size_mb: float):
    """生成接近目标大小的 PDF 文件"""
    target_size = int(target_size_mb * 1024 * 1024)

    c = canvas.Canvas(file_path, pagesize=A4)
    width, height = A4

    styles = getSampleStyleSheet()
    style = styles['Normal']

    paragraph_text = (
        "RAGGG 性能测试文档 - This is a stress test document generated for performance testing. "
        "The document contains multiple paragraphs to simulate a real-world scenario. "
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. "
        "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. "
        "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. "
        "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. "
        "测试文档内容 - 集群日志管理模块面试题参考回答。Kafka作为消息队列在日志收集中间承担缓冲层角色，"
        "采集端和消费端完全解耦。Elasticsearch 全文检索能力强，查询语言支持 ES|QL。Flink 任务日志通过"
        "log4j2 动态配置采集，Grok 正则解析任务 ID。消费端支持平滑降级，Kafka 不可用时切换本地文件。"
    )

    current_size = 0
    page_num = 1

    while current_size < target_size:
        c.setFont("Helvetica-Bold", 16)
        c.drawString(2*cm, height - 2*cm, f"RAGGG 压测文档 - 第 {page_num} 页")

        c.setFont("Helvetica", 11)
        y = height - 3*cm
        lines_per_page = 0

        while y > 3*cm and current_size < target_size:
            text = f"[页{page_num}段{lines_per_page+1}] {paragraph_text}"
            # 控制每行长度
            max_chars = 90
            text = text[:max_chars] + "..."

            c.drawString(2*cm, y, text)
            y -= 0.6*cm
            lines_per_page += 1
            current_size += len(text)

        c.drawString(2*cm, 2*cm, f"页码: {page_num} | 生成时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")
        c.showPage()
        page_num += 1

    c.save()


# ========== Prometheus 指标查询 ==========

def query_prometheus(query: str, timeout: int = 10) -> Optional[float]:
    """向 Prometheus 查询单个瞬时向量值，返回浮点数或 None"""
    url = f"{CONFIG['prometheus_url']}/api/v1/query"
    try:
        import urllib.request
        import urllib.error
        req = urllib.request.Request(url, data=urllib.parse.urlencode({"query": query}).encode())
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read())
            if data.get("status") == "success" and data["data"]["result"]:
                return float(data["data"]["result"][0]["value"][1])
    except Exception as e:
        print(f"    ⚠️  Prometheus 查询失败 [{query[:60]}]: {e}")
    return None


def pct_from_histogram(query_template: str, percentile: float, window: str = "5m") -> Optional[float]:
    """
    从 Prometheus 直方图查询指定百分位数（返回毫秒）。
    query_template 包含占位符 {le} 和 {window}
    """
    query = query_template.format(le=percentile, window=window)
    val = query_prometheus(query)
    if val is not None and val > 0:
        return round(val * 1000, 1)  # Prometheus timer 以秒存储，转毫秒
    return None


def rate_counter(query: str, window: str = "5m") -> Optional[float]:
    """查询 counter 速率（req/s 或 chunks/s）"""
    q = f"sum(rate({query}[{window}]))"
    return query_prometheus(q)


def current_value(query: str) -> Optional[float]:
    """查询 Gauge 当前值"""
    return query_prometheus(query)


def print_prometheus_snapshot():
    """每轮打印 Prometheus 实时指标摘要（定位瓶颈）"""
    print("\n  📡 Prometheus 实时指标:")
    lines = []

    def row(label, val, unit="ms"):
        lines.append(f"    {label:<30} {str(val) + ' ' + unit if val is not None else 'N/A':>20}")

    # --- 上传链路 ---
    for stage, desc in [("minio", "MinIO 上传"), ("db_save", "DB 保存"), ("kafka_send", "Kafka 发送")]:
        q = f'histogram_quantile(0.95, sum(rate(doc_upload_stage_seconds_bucket{{stage="{stage}"}}[5m])) by (le))'
        v = query_prometheus(q)
        row(desc, v, "ms")

    # --- Pipeline 各阶段 ---
    for stage, desc in [("parse", "解析"), ("chunk", "分块"), ("index", "索引")]:
        q = f'histogram_quantile(0.95, sum(rate(doc_pipeline_seconds_bucket{{stage="{stage}"}}[5m])) by (le))'
        v = query_prometheus(q)
        row(desc, v, "ms")

    # --- Index 每 Chunk 操作 ---
    for op, desc in [("embed_batch", "Embedding(批量)"), ("persist", "持久化")]:
        q = f'histogram_quantile(0.95, sum(rate(doc_pipeline_chunk_ops_seconds_bucket{{op="{op}"}}[5m])) by (le))'
        v = query_prometheus(q)
        row(desc, v, "ms")

    # --- QPS ---
    for q_name, q_expr, unit in [
        ("上传 QPS", "sum(rate(doc_upload_seconds_count[5m]))", "req/s"),
        ("解析 QPS", "sum(rate(doc_pipeline_seconds_count{stage=\"parse\"}[5m]))", "doc/s"),
        ("Chunk 吞吐", "sum(rate(doc_pipeline_chunk_count_total{status=\"success\"}[5m]))", "chunks/s"),
    ]:
        v = query_prometheus(q_expr)
        row(q_name, v, unit)

    for line in lines:
        print(line)
    print()


# ========== 认证 & HTTP 客户端 ==========

async def get_auth_token(session: aiohttp.ClientSession) -> Optional[str]:
    """登录获取 token"""
    try:
        url = f"{CONFIG['base_url']}{CONFIG['api_prefix']}/auth/login"
        data = {"username": CONFIG["username"], "password": CONFIG["password"]}

        async with session.post(url, json=data, timeout=aiohttp.ClientTimeout(total=10)) as resp:
            if resp.status == 200:
                result = await resp.json()
                return result.get("data", {}).get("token") or result.get("token")
            else:
                # guest 模式, 可能不需要 token
                print(f"  [认证] 登录失败 status={resp.status}, 尝试 guest 模式")
                return None
    except Exception as e:
        print(f"  [认证] 异常: {e}")
        return None


async def upload_document(session: aiohttp.ClientSession, file_path: str, kb_id: int, token: Optional[str],
                          chunk_strategy: str = "fixed") -> UploadResult:
    """上传单个文档"""
    file_name = os.path.basename(file_path)
    file_size = os.path.getsize(file_path)

    url = f"{CONFIG['base_url']}{CONFIG['api_prefix']}/documents/upload"

    content_type_map = {
        ".pdf": "application/pdf",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".doc": "application/msword",
        ".txt": "text/plain",
        ".md": "text/markdown",
        ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    }
    ext = os.path.splitext(file_name)[-1].lower()
    content_type = content_type_map.get(ext, "application/octet-stream")

    form = aiohttp.FormData()
    form.add_field("file", open(file_path, "rb"),
                   filename=file_name,
                   content_type=content_type)
    form.add_field("kbId", str(kb_id))
    form.add_field("chunkStrategy", chunk_strategy)

    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    start = time.time()
    try:
        async with session.post(url, data=form, headers=headers,
                                timeout=aiohttp.ClientTimeout(total=60)) as resp:
            latency = (time.time() - start) * 1000

            body = await resp.text()
            if resp.status in [200, 202, 201]:
                try:
                    data = json.loads(body)
                    doc_id = data.get("documentId", "")
                    trace_id = data.get("traceId", "")
                    status = data.get("status", "UNKNOWN")
                    return UploadResult(
                        file_name=file_name,
                        document_id=doc_id,
                        upload_latency_ms=latency,
                        trace_id=trace_id,
                        status=status
                    )
                except:
                    return UploadResult(
                        file_name=file_name,
                        document_id="unknown",
                        upload_latency_ms=latency,
                        trace_id="",
                        status="UPLOADED"
                    )
            else:
                print(f"\n  ❌ 上传失败: HTTP {resp.status}: {body[:500]}")
                return UploadResult(
                    file_name=file_name,
                    document_id="",
                    upload_latency_ms=latency,
                    trace_id="",
                    status="FAILED",
                    error=f"HTTP {resp.status}: {body[:200]}"
                )
    except asyncio.TimeoutError:
        return UploadResult(
            file_name=file_name, document_id="", upload_latency_ms=60000,
            trace_id="", status="TIMEOUT", error="Upload timeout (>60s)"
        )
    except Exception as e:
        return UploadResult(
            file_name=file_name, document_id="", upload_latency_ms=(time.time() - start) * 1000,
            trace_id="", status="ERROR", error=str(e)
        )


async def poll_document_status(session: aiohttp.ClientSession, doc_id: str, token: Optional[str]) -> tuple:
    """轮询文档状态, 返回 (status, parse_latency_ms)"""
    url = f"{CONFIG['base_url']}{CONFIG['api_prefix']}/documents/{doc_id}/status"
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    poll_start = time.time()
    interval = CONFIG["poll_interval_ms"] / 1000

    while True:
        elapsed_ms = (time.time() - poll_start) * 1000
        if elapsed_ms > CONFIG["poll_timeout_ms"]:
            return "TIMEOUT", None

        try:
            async with session.get(url, headers=headers,
                                   timeout=aiohttp.ClientTimeout(total=10)) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    status = data.get("status", "UNKNOWN")

                    if status in ["COMPLETED", "COMPLETE", "INDEXED"]:
                        return status, (time.time() - poll_start) * 1000
                    elif status in ["FAILED", "ERROR", "PARSE_FAILED"]:
                        return status, None

                    await asyncio.sleep(interval)
                else:
                    await asyncio.sleep(interval)
        except Exception as e:
            await asyncio.sleep(interval)


def scan_real_docs(folder: str) -> list[UploadRequest]:
    """扫描真实文档目录，返回 UploadRequest 列表"""
    if not os.path.isdir(folder):
        print(f"  ⚠️  目录不存在: {folder}")
        print(f"     将创建目录并退出，请放入真实文档后重新运行")
        os.makedirs(folder, exist_ok=True)
        return []
    files = []
    for f in sorted(os.listdir(folder)):
        ext = os.path.splitext(f)[-1].lower()
        if ext in SUPPORTED_EXTENSIONS:
            path = os.path.join(folder, f)
            size_mb = os.path.getsize(path) / (1024 * 1024)
            files.append(UploadRequest(
                file_name=f,
                file_size_mb=round(size_mb, 2),
                file_path=path,
                chunk_strategy=CONFIG["chunk_strategy"]
            ))
    return files


async def worker(
    worker_id: int,
    files: list,
    session: aiohttp.ClientSession,
    token: Optional[str],
    result_queue: asyncio.Queue,
    start_barrier: asyncio.Event,
    file_iter: asyncio.Lock
):
    """单个压测 worker: 轮询上传文件夹内所有文件，支持多轮循环"""
    await start_barrier.wait()

    request_count = 0
    file_index = worker_id  # 每个 worker 从不同文件开始，均匀分布

    while True:
        # 总请求数限制
        if CONFIG["total_requests"] > 0 and request_count >= CONFIG["total_requests"]:
            break

        # 轮询选文件（均匀分布，不重复随机）
        async with file_iter:
            if not files:
                break
            f = files[file_index % len(files)]
            file_index += CONFIG["concurrency"]

        # 上传（使用该文件对应的分块策略）
        result = await upload_document(
            session, f.file_path, CONFIG["kb_id"], token, f.chunk_strategy)

        if result.document_id:
            status, parse_latency = await poll_document_status(session, result.document_id, token)
            result = UploadResult(
                file_name=result.file_name,
                document_id=result.document_id,
                upload_latency_ms=result.upload_latency_ms,
                trace_id=result.trace_id,
                status=status,
                parse_latency_ms=parse_latency,
                error=result.error if status in ("FAILED", "TIMEOUT") else None
            )

        await result_queue.put(result)
        request_count += 1


async def prometheus_poller(poll_seconds: int):
    """后台任务：定期打印 Prometheus 指标"""
    while True:
        await asyncio.sleep(poll_seconds)
        elapsed = time.strftime("%M:%S")
        print(f"\n  ⏱ [{elapsed}] ===== Prometheus 实时指标 =====")
        print_prometheus_snapshot()


async def run_stress_test():
    """主压测流程"""
    mode = TEST_MODE
    print("\n" + "=" * 80)
    print("  🚀 RAGGG 文档上传全链路压测 v3")
    print("=" * 80)
    print(f"  测试模式:     {'真实文档' if mode == 'folder' else '自动生成 PDF'}")
    print(f"  并发数:       {CONFIG['concurrency']}")
    print(f"  目标请求数:   {CONFIG['total_requests'] if CONFIG['total_requests'] > 0 else '无限制（轮完文件夹）'}")
    print(f"  轮询超时:     {CONFIG['poll_timeout_ms']/1000}s")
    print(f"  知识库ID:     {CONFIG['kb_id']}")
    print(f"  分块策略:     {CONFIG['chunk_strategy']}")
    print(f"  Prometheus:   {CONFIG['prometheus_url']}")
    print(f"  Grafana:      http://localhost:23000")
    print("=" * 80)

    # ---- 获取文件列表 ----
    if mode == "folder":
        print(f"\n📂 扫描真实文档目录: {REAL_DOCS_FOLDER}")
        files = scan_real_docs(REAL_DOCS_FOLDER)
        if not files:
            print(f"\n  目录为空或不存在，已退出。请将真实文档放入该目录后重新运行。")
            print(f"  支持格式: {', '.join(SUPPORTED_EXTENSIONS)}")
            return
        print(f"\n  找到 {len(files)} 个文件:")
        for f in files:
            print(f"    [{f.chunk_strategy:8s}]  {f.file_name:<40s}  {f.file_size_mb:.2f} MB")
        print(f"\n  每个文件将使用分块策略: {CONFIG['chunk_strategy']}")
        print(f"  切换策略请用 --chunk-strategy=recursive")
    else:
        print("\n📁 生成测试 PDF 文件...")
        test_data_dir = os.path.join(os.path.dirname(__file__), "test-data")
        files = generate_test_pdfs(test_data_dir, len(GENERATED_FILE_SIZES_MB))
        if not files:
            print("  生成失败")
            return
        print(f"  生成 {len(files)} 个测试文件")
        for f in files:
            print(f"    {f.file_name:<40s}  {f.file_size_mb:.2f} MB")

    # ---- Prometheus 检查 ----
    prometheus_available = False
    try:
        import urllib.request
        with urllib.request.urlopen(f"{CONFIG['prometheus_url']}/-/healthy", timeout=5):
            prometheus_available = True
            print("\n  ✅ Prometheus 可达")
    except Exception:
        print("\n  ⚠️  Prometheus 不可达，跳过实时指标轮询")
        print("     启动: docker compose up -d prometheus grafana")

    # ---- HTTP Session + 认证 ----
    connector = aiohttp.TCPConnector(limit=CONFIG["concurrency"] * 2, force_close=True)
    async with aiohttp.ClientSession(connector=connector) as session:
        print("\n🔑 获取认证 Token...")
        token = await get_auth_token(session)
        if token:
            print(f"  Token: {token[:20]}...")
        else:
            print("  Token 获取失败，使用 guest 模式")

        print("\n🔍 测试后端连接...")
        try:
            async with session.get(f"{CONFIG['base_url']}{CONFIG['api_prefix']}/documents",
                                   timeout=aiohttp.ClientTimeout(total=5)) as resp:
                print(f"  后端正常, status={resp.status}")
        except Exception as e:
            print(f"  ❌ 后端连接失败: {e}")
            print("  请确认后端已启动: mvn spring-boot:run")
            return

        # ---- 报告 & Workers ----
        report = StressTestReport()
        result_queue = asyncio.Queue()
        start_barrier = asyncio.Event()
        file_iter = asyncio.Lock()  # 保护文件轮询索引

        poller = None
        if prometheus_available:
            poller = asyncio.create_task(prometheus_poller(CONFIG["prometheus_poll_seconds"]))

        print(f"\n⚡ 启动 {CONFIG['concurrency']} 个并发 worker...")
        workers = [
            asyncio.create_task(worker(i, files, session, token, result_queue, start_barrier, file_iter))
            for i in range(CONFIG["concurrency"])
        ]

        report.start_time = time.time()
        start_barrier.set()

        # ---- 收集结果 ----
        collected = 0
        while True:
            timeout = CONFIG["duration_seconds"] if CONFIG["duration_seconds"] > 0 else 999999
            try:
                result = await asyncio.wait_for(result_queue.get(), timeout=5)
                report.add_result(result)
                collected += 1
                report.print_report()
                if CONFIG["total_requests"] > 0 and collected >= CONFIG["total_requests"]:
                    break
            except asyncio.TimeoutError:
                if CONFIG["duration_seconds"] > 0 and (time.time() - report.start_time) >= CONFIG["duration_seconds"]:
                    break

        report.end_time = time.time()

        if poller:
            poller.cancel()
        for w in workers:
            if not w.done():
                w.cancel()

        # 最终报告（包含 Prometheus 指标）
        report.final_report(prometheus_available=prometheus_available)


def main():
    print("""
╔════════════════════════════════════════════════════════╗
║   RAGGG 文档上传全链路压测脚本 v3                        ║
║   模式: 真实文档文件夹 或 自动生成 PDF                   ║
╚════════════════════════════════════════════════════════╝
    """)

    # 解析命令行参数覆盖配置
    for arg in sys.argv[1:]:
        if arg.startswith("--concurrency="):
            CONFIG["concurrency"] = int(arg.split("=")[1])
        elif arg.startswith("--requests="):
            CONFIG["total_requests"] = int(arg.split("=")[1])
        elif arg.startswith("--duration="):
            CONFIG["duration_seconds"] = int(arg.split("=")[1])
        elif arg.startswith("--url="):
            CONFIG["base_url"] = arg.split("=")[1]
        elif arg.startswith("--kb="):
            CONFIG["kb_id"] = int(arg.split("=")[1])
        elif arg.startswith("--chunk-strategy="):
            CONFIG["chunk_strategy"] = arg.split("=")[1]
        elif arg.startswith("--mode="):
            global TEST_MODE
            TEST_MODE = arg.split("=")[1]
        elif arg == "--help":
            print("""
用法: python document-upload-stress.py [选项]

选项:
  --concurrency=N         并发数 (默认: 5)
  --requests=N            总请求数，0=轮完文件夹 (默认: 0)
  --duration=N            持续时间(秒)，0=按requests (默认: 0)
  --chunk-strategy=STR    分块策略: fixed / recursive / semantic (默认: fixed)
  --mode=MODE             测试模式: folder / generated (默认: folder)
  --url=http://...        后端地址 (默认: http://localhost:8081)
  --kb=N                  知识库ID (默认: 1)

示例:
  # 真实文档压测（把文件放到 stress-test/real-docs/）
  python document-upload-stress.py --concurrency=5 --chunk-strategy=fixed

  # 自动生成 PDF 测试
  python document-upload-stress.py --mode=generated --concurrency=3
            """)
            return

    asyncio.run(run_stress_test())


if __name__ == "__main__":
    main()