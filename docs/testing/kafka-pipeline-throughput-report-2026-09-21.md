# Kafka 文档流水线吞吐测试报告

- **日期**：2026-09-21
- **测试人**：ZCode 自动化压测
- **被测系统**：RAGGG（rag-demo-1.0.0-SNAPSHOT，当日晚重新构建）
- **测试目的**：测量「上传 → Kafka → 解析 → 分块 → 索引」全链路吞吐，定位瓶颈

---

## 1. 测试环境

### 1.1 硬件与系统

| 项 | 值 |
|---|---|
| CPU | 40 核 x86_64 |
| 内存 | 62 GB（测试期间可用 ~38 GB） |
| 操作系统 | Linux 6.8.0-136-generic (Ubuntu 22.04, Java 17.0.20) |

### 1.2 服务拓扑

| 组件 | 运行方式 | 地址 |
|---|---|---|
| 后端 RAGDemo | jar 后台进程，`-Xmx4g`，PID 4068694 | localhost:8080 |
| Kafka (raggg-kafka) | Docker，KRaft 单节点 | localhost:29292 |
| Milvus (clean-milvus) | Docker v2.6.6 + etcd + MinIO 独立栈 | localhost:19530 |
| Elasticsearch (rag-es) | Docker 8.x 单节点（yellow） | localhost:9200 |
| MySQL (rag-mysql) | Docker 8.0 | localhost:3307 |
| Redis (raggg-redis) | Docker 7.4.8 | localhost:26379 |
| MinIO (rag-minio) | Docker | localhost:9001 |
| **Ollama** | **宿主机进程**，bge-m3，`-np 1 -c 4096 -b 2048` | localhost:11434 |

### 1.3 关键配置（config.yaml + application.yml）

- embedding.provider=**ollama**（bge-m3，1024 维），`embedding.batch-size=64`
- Kafka 消费者：`setConcurrency(3)`，AckMode=BATCH，max-poll-records=1
- 分块：fixed 策略，chunk-size 512
- **topic 实际分区数：`document-upload` / `document-parsed` / `document-chunked` 各 1 个分区**（消费组 3 个 consumer 只有 1 个分到分区）

---

## 2. 测试方法

自包含压测脚本 `/tmp/kafka_pipeline_bench.py`（仅依赖 requests）：

1. 登录 admin → 建知识库（id=18）
2. 生成 N 份合成中文技术文档（段落语料随机拼接）
3. 以 C 并发调用 `POST /api/v1/documents/upload`（multipart，fixed 策略）
4. 每 2s 轮询 `GET /api/v1/documents?kbId=18`，记录每个文档每个状态（PENDING→…→COMPLETED）的首次出现时间戳
5. 全部到达终态后计算吞吐与阶段耗时，明细存 JSON

| 场景 | 文档数 | 单文档大小 | 上传并发 | 用途 |
|---|---|---|---|---|
| S0 冒烟 | 3 | 32 KB | 3 | 验证链路通畅 |
| **S1 正式** | **50** | **64 KB** | **5** | 主吞吐测量 |
| S2 对照 | 10 | 64 KB | **1** | 验证"上传并发不影响吞吐" |
| S3 微基准 | — | ~300 字符/条 × {1,16,32,54,64,128} 条 | 直连 Ollama `/api/embed` | 隔离嵌入服务能力 |

辅助数据源：`/actuator/prometheus`（doc_pipeline_* / doc_upload_*）、后端日志（traceId 贯穿）、ES `_count`。

---

## 3. 测试结果

### 3.1 主场景 S1（50 docs × 64KB，c=5）

| 指标 | 数值 |
|---|---|
| 成功 / 失败 | **50 / 0**（消息零丢失） |
| 总分块 | 2697（54 块/文档），总文本 1.27M 字符 |
| 上传阶段 | 50 请求共 **0.5 s**（≈100 req/s，p50 33 ms） |
| 端到端墙钟 | 181.8 s；首文档 4.6 s 完成，末文档 181.8 s |
| **吞吐** | **16.5 docs/min = 890 chunks/min = 1.03 MB/min** |
| 排队等待（wait INDEXING 前） | 均值 89 s，最大 173 s |

### 3.2 对照场景 S2（10 docs，c=1）

| 指标 | c=5（S1） | c=1（S2） |
|---|---|---|
| 吞吐 docs/min | 16.5 | **16.37** |
| 吞吐 chunks/min | 889.9 | **880.6** |

**上传并发从 1 提到 5，吞吐完全不变**——消费侧串行是硬上限，API 层余量巨大（c=1 时单请求 p50 仅 50 ms）。

### 3.3 各阶段服务耗时（Prometheus 累计值，53 docs 总量）

| 阶段 | 总耗时 | 单文档均值 | 占比 |
|---|---|---|---|
| 上传 API（minio+db+kafka） | 1.68 s | 32 ms | 0.8% |
| Parse（Tika） | 3.04 s | **57 ms** | 1.5% |
| Chunk（fixed 512） | 2.92 s | **55 ms** | 1.4% |
| **Index（embed+三库写入）** | **197.15 s** | **3.72 s** | **96.8%** |
| 流水线服务时间合计 | 203.1 s | 3.83 s | 100% |

Index 阶段内部（`doc_pipeline_chunk_ops_seconds`）：

| 操作 | 总耗时 | 单文档 | 说明 |
|---|---|---|---|
| **embed_batch（Ollama）** | **193.26 s** | **3.65 s** | 占全流水线 **95.1%** |
| persist（MySQL+ES Bulk+Milvus RPC） | 3.19 s | 60 ms | 三库合计可忽略 |
| MinIO 下载+上传（流水线内） | 4.47 s / 106 ops | ~42 ms/op | 可忽略 |

数据一致性验证：ES `rag_documents` 中 kb_id=18 计数 2778 = 冒烟 81 + 正式 2697，**精确吻合**；Milvus 每文档一条 `Batch inserted 54 chunks` 日志共 53 条，零失败。

### 3.4 嵌入微基准 S3（直连 Ollama，绕开应用）

| 批大小 | 耗时 | chunks/s |
|---|---|---|
| 1 | 0.23 s | 4.3 |
| 32 | 1.60 s | 20.0 |
| 54 | 2.62 s | 20.6 |
| 64 | 3.00 s | 21.3 |
| 128 | 5.75 s | 22.3 |

与流水线内观测（54 条/批 ≈ 3.65 s，即 14.8 chunks/s）同数量级（差额来自真实 chunk 更长 + 应用侧开销）。**批大小低于 32 时嵌入吞吐急剧劣化**；批内并行收益在 64 后趋平（~22 chunks/s 封顶）。

CPU 采样：嵌入期间 llama-server 仅用 **~35% 单核**（`-np 1` 单槽位），远未饱和——瓶颈在 Ollama 单流推理（内存带宽型负载），不是 CPU 核数不足。

---

## 4. 瓶颈分析

### 4.1 逐层下钻定位

```
上传并发5 vs 1 吞吐相同        → 不是 API/上传层（余量 >100 req/s）
  └─ 三阶段总耗时 parse 57ms / chunk 55ms / index 3.72s
       → 是 Index 阶段（占 96.8%）
            └─ embed_batch 3.65s vs persist 60ms
                 → 是 Embedding 调用（占 95.1%）
                      └─ 直连 Ollama 复现 ~20 chunks/s
                           → 是 Ollama bge-m3 CPU 单流推理能力
```

**最终瓶颈：索引阶段的 Ollama 本地 bge-m3 嵌入（CPU 推理），占全流水线服务时间的 95.1%。**

### 4.2 三层结构性放大因素

1. **Embedding 本身慢**：bge-m3 在 CPU 上单流约 20 chunks/s（64 条/批），1.27M 字符要 193 s。
2. **消费完全串行**：每 topic 仅 1 分区，`setConcurrency(3)` 形同虚设，50 个文档在 index 阶段排队（末位等待 173 s）。吞吐 = 单文档服务时间的倒数（3.83 s/doc ≈ 15.7 docs/min，实测 16.5 吻合）。
3. **Ollama 单槽位 `-np 1`**：即使打开消费并发，并发嵌入请求也会在 Ollama 内部排队——扩分区的同时必须同步调 `OLLAMA_NUM_PARALLEL`，否则消费并发只是把排队从 Kafka 挪进 Ollama。

### 4.3 非瓶颈项（实测排除）

| 项 | 证据 |
|---|---|
| 上传 API / Kafka 生产 | 50 请求 0.5 s；kafka_send 累计 5 ms |
| Tika 解析 / 分块算法 | 各 57/55 ms 每文档（纯 txt；重 PDF 未测，见局限） |
| MySQL + ES + Milvus 写入 | 三库合计 60 ms/文档（54 块） |
| MinIO | 上传 28 ms、流水线内读写 ~42 ms/op |
| 机器资源 | 40 核只用 ~0.5 核，内存余量 38 GB |

### 4.4 顺带发现的代码问题（与吞吐无直接关系）

- `IndexService` 的"批量 embedding 完成"日志占位符未填充（输出 `batchSize={}, tookMs={}`）。
- Kafka 无重试/DLT：本次 0 失败，但消费者吞异常的设计下，失败文档只会停在 FAILED 无自动补偿。
- 消费 AckMode=BATCH + 自动提交：处理中途崩溃可能造成少量重复消费（幂等性依赖状态机检查）。

---

## 5. 扩容路径（按性价比排序，含预估）

| # | 措施 | 预估收益 | 代价 |
|---|---|---|---|
| 1 | **Embedding 换 SiliconFlow API**（bge-m3，config 切 provider） | 受网络/RPS 限制，通常 5–20× | 费用 + 外部依赖 |
| 2 | **topic 扩 3 分区** + Ollama `NUM_PARALLEL=3` | 理论 ~3×（≈50 docs/min），需 persist 不成瓶颈（当前余量 60 倍） | 需重建 topic / 调容器 env |
| 3 | GPU 跑 Ollama（bge-m3） | 单流 10–30× | 需要 GPU |
| 4 | 维持现状，接受 890 chunks/min | — | 适合日均文档量 < 1.2 万块的场景 |

组合建议：先做 #2（零成本、纯配置），若仍不够再上 #1。

---

## 6. 局限性说明

- 测试文档为纯文本 txt，**未覆盖大 PDF/OCR 路径**（Tika 解析在重文档上可能不再是 57ms 量级）。
- 单一文档尺寸（64KB/54 块），超大文档的分批行为未单独测。
- 轮询间隔 2s，parse/chunk 的中间态多数未被捕获（其耗时以 Prometheus 累计值为准）。
- 单机单副本 Kafka，未测 broker 层上限（当前远未触及）。

## 7. 原始数据索引

| 文件 | 内容 |
|---|---|
| `/tmp/kafka_pipeline_bench.py` | 压测脚本（可复用：-n/-c/-s/--kb-id） |
| `/tmp/bench_smoke.json` / `bench_main.json` / `bench_c1.json` | 三场景逐文档状态流转时间戳 |
| `/tmp/raggg-backend.log` | 后端全量日志（traceId 贯穿三阶段） |
| `GET :8080/actuator/prometheus` | doc_pipeline_* / doc_upload_* 指标 |
| 测试数据 | 知识库 id=18（kafka-throughput-bench），63 个 bench-doc-*.txt |
