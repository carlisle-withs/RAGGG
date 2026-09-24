# 运维手册

> **文档性质**：日常运维、工具链使用与故障排查，2026-09-24 整理。
> 部署见 [02-deployment](./02-deployment.md)；配置见 [配置参考](../configuration-reference.md)；指标体系见 [01-architecture-overview 第八节](../architecture/01-architecture-overview.md)。

## 一、工具链

### 1.1 test-agent.sh —— 端到端功能测试代理（61 端点）

bash + curl + python3 实现的冒烟测试，按 15 个模块断言：健康检查、认证（4）、用户、对话、会话（5）、知识库 CRUD（7）、文档（10）、文档管理（5）、检索（2）、Embedding（2）、评估（2）、流水线（2）、RAG（6）、RAG v3 SSE（1，旧链路）、示例问题（4）、仪表盘（3）。

```bash
./test-agent.sh             # 完整运行（彩色 PASS/FAIL/SKIP 计数）
./test-agent.sh --quick     # 跳过 LLM 调用
./test-agent.sh --verbose   # 详细输出
```

> 注意：安全收紧后测试代理先登录拿 token 再调用（其内部已按此设计）；v3 SSE 一节测的是废弃链路，失败不阻断。

### 1.2 watchdog.sh —— 开发守护进程

文件变更 → 自动构建 → 自动测试的闭环：

```
inotifywait 监听 src/ 与 frontend/src（3s 防抖）
  → 前端 npm run build 并拷贝 dist 到 Spring static
  → mvn package -DskipTests
  → pkill 旧 jar → java -Xmx2g 重启
  → 30s 轮询健康检查
  → test-agent.sh --quick
```

```bash
./watchdog.sh               # 前台
./watchdog.sh --daemon      # 后台
./watchdog.sh status|stop   # 状态/停止（PID 与日志在 /tmp）
```

⚠️ 注意：watchdog 会把前端构建产物拷进 `src/main/resources/static/`——**提交前确认 static/ 未被意外入库**（known-gaps 🟢 记录的构建产物入库风险）。

### 1.3 stress-test/ —— 压测与评测工具集

**完整用法见 [stress-test/README.md](../../stress-test/README.md)**（脚本说明 + 参数 + 示例）。常用入口：

| 场景 | 脚本 |
|---|---|
| 上传链路压测 | `document-upload-stress.py`（并发/状态轮询/P50-P99） |
| 检索召回评测 | `eval_pubmedqa_recall.py` / `eval_dataset_recall.py`（通用模板，--docs/--queries 参数化） |
| 中文基准 | `benchmark_crudd_local.py`（CRUD-RAG 官方集，content_hit 逐字符判定） |
| 混合检索对比 | `benchmark_hybrid.py`（含直连 ES/pymilvus 三路对比、--rewrite 模糊化查询） |
| RAGAS 全链路 | `eval_ragas_pipeline.py`（生成 + 判分两阶段） |
| 指标推送 | `push_metrics.py`（Pushgateway :19991） |

脚本依赖 `requests`（`pip install -r requirements.txt`），SiliconFlow key 经环境变量注入（**不要写进脚本**，历史泄露教训）。

### 1.4 scripts/ —— 构建辅助

`check-pom.ps1` / `read-lines.ps1` / `check2.ps1` / `debug-pom.ps1`：Windows PowerShell 的 pom 调试辅助（历史遗留，Linux 下不需要）。

## 二、监控与告警锚点

### 2.1 Prometheus 抓取

`monitoring/prometheus/prometheus.yml`：rag-backend（host.docker.internal:8080 `/actuator/prometheus`，**5s 间隔**）+ pushgateway（:19991）+ 自身。

### 2.2 Grafana 面板目录（rag-pipeline.json，20 面板，provisioning 自动加载）

| 分组 | 面板 |
|---|---|
| 概览 stat | 上传成功/失败数、解析成功/跳过、索引成功、chunk 索引成功（5m increase）、上传/索引 P95 延迟 |
| 延迟时序 | 上传链路 P50/P95/P99（doc_upload_seconds_bucket）、Pipeline 各阶段（doc_pipeline_seconds_bucket{stage=parse/chunk/index}） |
| **逐 chunk 操作** | doc_pipeline_chunk_ops_seconds_bucket{op=embed_batch/mysql_save/elasticsearch...} 的 P95——**定位 embed 瓶颈（95.1%）用的正是这组** |
| 吞吐 | 各阶段 QPS、chunks/s 成功失败 |
| 并发/分布 | 活跃处理中文档数（doc_pipeline_inprogress）、解析文本长度与分块数分布直方图 |

**覆盖边界**：仅文档流水线。检索（`rerank_fallback_total`）、Kafka（`kafka_produce_fail_total`）、记忆（`memory.*`）指标已埋点但**无面板**——PromQL 手查或后续补板。

### 2.3 三个告警锚点（建议配 Prometheus 告警规则）

| 指标 | 含义 | 动作 |
|---|---|---|
| `rate(rerank_fallback_total[5m]) > 0` | rerank 静默回退（key 失效/API 故障） | 检查 reranker.api-key |
| `rate(kafka_produce_fail_total[5m]) > 0` | 事件发送失败（消息丢失，无补偿） | 检查 Kafka 可用性，必要时重传 |
| 文档长时间停留非终态 | 状态机卡住（如 MinIO 异常） | `GET /documents?kbId=` 轮询 + 日志 |

## 三、故障排查 Runbook

### 3.1 服务起不来 / 连不上中间件

| 症状 | 排查 |
|---|---|
| 端口 8080 拒绝 | 无 config.yaml → 服务在 **8081**；查 `server.port` 生效值 |
| MySQL/ES/Milvus 连接失败 | config.yaml 端口 vs compose 映射（23306/29201/29530 系）；`server.ip` 是否指向正确宿主 |
| Kafka 连上但流水线不动 | compose 的 `KAFKA_ADVERTISED_LISTENERS` 硬编码内网 IP——非原机器必须改 |
| 启动日志 "dropping and recreating" | Milvus 维度变更或缺 metadata 字段触发重建——**向量已清空，需重灌语料** |

### 3.2 检索质量异常

| 症状 | 排查 |
|---|---|
| 全部查询无结果 | ollama 模式未 pull bge-m3；或 kbId 无数据（`GET /documents?kbId=`） |
| 排序"变笨"（无精排效果） | 查 `rerank_fallback_total` 指标是否增长——SiliconFlow key 失效会**静默**回退原序 |
| 中文查询召回差 | 确认走 hybrid（BM25+向量）；可用 `/mappings/preview` 验证术语归一化；`--rewrite` 模糊化测试见 benchmark_hybrid |
| hierarchical 无合并效果 | 确认语料在 metadata 字段上线**之后**灌入（旧数据无父子元数据，只会走直通） |

### 3.3 流水线异常

| 症状 | 排查 |
|---|---|
| 文档卡在 INDEXING | 修复后此路径已置 FAILED；若仍见卡住，查 MinIO（chunks.json 拉取异常）与消费者日志 |
| 上传成功但无 chunks | ChunkService 日志（策略/参数）；上传时 chunkStrategy 参数是否合法 |
| 部分库有数据部分没有 | 三存储部分写（历史遗留数据）；`POST /knowledge-base/{kbId}/reindex` 重建 |

### 3.4 会话/记忆异常

| 症状 | 排查 |
|---|---|
| 重启后会话丢失 | 正常不应丢（MySQL 回源）；查 Redis 连接与 `memory.rebuild.total` 指标 |
| 历史消息无引用 | sources 列上线前的旧消息无 sources；新消息查 t_message.sources 与 /messages 返回 |
| 长对话上下文异常膨胀 | 不会——token 预算硬上界（记忆 3000 + 检索 4000），查 `memory.context.tokens` |

### 3.5 安全相关

| 症状 | 排查 |
|---|---|
| 前端全部 401 | token 过期（24h）→ 重新登录；确认 Nginx 转发了 Authorization 头 |
| 403 on /chat | KB 归属校验（非 admin 只能访问自己创建的 KB） |
| ReAct SQL 被拒 | 正常——护栏生效；查 warn 日志 "Rejected SQL by guardrail"（含原因） |

## 四、评测基线维护

**⚠️ 当前基线的时效提示**：testing/ 目录的评测数字（PubMedQA Hit@10=100%、CRUD-RAG 73.5%、RAGAS 0.785）测于 2026-09-24 修复**之前**的代码。SWA 修复、rerank 混排修复、词映射接入、上下文预算均可能改变结果。**重大变更后建议重跑**：

```bash
cd stress-test
python eval_pubmedqa_recall.py --kb-id 19    # 复用已有库跳过灌库
python eval_ragas_pipeline.py                # RAGAS 全链路
```

重跑后新报告落 `stress-test/reports/` 或 docs/testing/（带日期），旧报告不删（历史演进记录）。
