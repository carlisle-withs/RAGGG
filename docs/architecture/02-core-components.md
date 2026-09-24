# 核心模块详解

> **文档性质**：系统核心模块的**现状实现说明**，2026-09-24 与代码对账重写。每个模块给出：解决什么问题、怎么实现（类/配置/指标）、实测数据（若有）、当前边界。
> 总览见 [01-architecture-overview](./01-architecture-overview.md)；缺陷对账见 [known-gaps](../known-gaps.md)。

本文覆盖七个核心模块：**① Kafka 异步流水线 ② 统一检索管线 ③ 混合检索 + 精排 + 层级检索 ④ 两级会话记忆 ⑤ ReAct 推理引擎 ⑥ RAGAS 评测 ⑦ 分布式限流（已实现待接入）**。

---

## 一、知识库构建：Kafka 异步解耦流水线

### 解决什么问题

长文档（100+ 页 PDF）同步解析导致 API 超时；突发上传吞吐受限。

### 实现

三个 topic（`document-upload → document-parsed → document-chunked`）各配独立 consumer group（`rag-system-parse/chunk/index`），可独立扩容；**Kafka 消息只传 MinIO 路径引用**（parsed.txt / chunks.json 作为阶段间大对象缓冲），消息体极小。

关键设计：

1. **AFTER_COMMIT 发布**：`@TransactionalEventListener(phase = AFTER_COMMIT)` 保证 DB 事务提交后才发 Kafka——消费者不会读到"文档已删除"的未提交数据（历史上因此误判过，注释里有记录）
2. **批量索引**：IndexService 按 `embedding.batch-size`（默认 32）攒批，一次 embedBatch + MySQL saveAll + ES Bulk + Milvus insertBatch——**1 次 API 调用替代 N 次**，这是上传 P95 从 100.6s 降到 3.0s 的关键（见 [压力测试报告](../testing/压力测试报告.md)）
3. **事务真实生效**（2026-09-24 修复）：`@KafkaListener` 方法直接标注 `@Transactional`——此前 `this.doProcess()` 自调用绕过代理，事务形同虚设
4. **失败语义**：业务失败置 FAILED 即终态（有意设计）；基础设施异常走 `DefaultErrorHandler`（1s × 2 重试）；无 chunks 时置 FAILED（不再卡死 INDEXING）
5. **实战防御**：孤立 UTF-16 代理清洗（防 Milvus protobuf 崩溃）、content 60000 截断、空白 chunk 跳过（防 embedding API 20015）

### 实测数据

[testing/kafka-pipeline-throughput-report-2026-09-21](../testing/kafka-pipeline-throughput-report-2026-09-21.md)：16.5 docs/min = 890 chunks/min，零失败；瓶颈下钻定位 **95.1% 耗时在本地 Ollama embedding 单流**（Parse 57ms / Chunk 55ms / 三库持久化 60ms 均可忽略）；扩容路径：topic 扩 3 分区（理论 ~3×）或换 SiliconFlow embedding（5-20×）。

---

## 二、统一检索管线（RagContextService）

### 解决什么问题

历史上同步/流式/v3 三条对话链路各写一份检索逻辑，漂移出"v3 缺指代消解、流式缺查询扩展"两类 bug。

### 实现

同步链路（ChatApplicationService）、流式链路（DeepSeekStreamingService）、v3（RagChatController）**共用一个入口** `RagContextService.build(userMessage, memoryContext, kbId)`：

```
指代消解（有记忆才做，省一次 LLM 调用）
  → 词映射归一化（QueryTermService：t_query_term_mapping 规则，60s 内存缓存，
     matchType 1=包含替换 2=整词替换，管理端 /api/v1/mappings 维护即失效缓存）
  → 查询扩展（QueryRewriter.expand，失败回退上一步结果）
  → hybridSearch(topK=5, rerank=true)
  → 上下文 token 预算（retrieval.context-max-tokens=4000，
     与记忆系统同口径估算，超预算按相关性截断——防 top-5 大块无限膨胀稀释注意力）
  → 步骤级 trace 落库（RagTraceRun + 5 节点，见总览第八节）
```

**每步降级**：消解失败→原查询；扩展失败→消解查询；检索失败→空上下文继续聊。**管线任何一步挂了都不阻断对话**。

---

## 三、双路召回 + RRF + CrossEncoder 精排 + 层级检索

### 混合检索（hybrid，默认）

`HybridRetrievalService`：Milvus（HNSW/IP，ef=128，filter kb_id）‖ ES（BM25 multi_match + term 过滤）双路并行各取 `candidate-pool-size=20`，RRF 融合 `Σ 1/(60+rank+1)`，**全部候选送精排**（RRF 只做粗排，不二次截断）。

**精排**：`SiliconFlowReranker`（BAAI/bge-reranker-v2-m3 真 CrossEncoder API）。两个 2026-09-24 修复的要点：
- `top_n` 一律传全部候选——此前按 topK 请求时，未进返回集的候选拿 RRF 分（~0.016 量级）当 relevance 与 0~1 分混排，排序错乱
- **静默回退可观测**：API 失败返回原序不抛错，但计数 `rerank_fallback_total{reason}`——PubMedQA 评测曾因 key 失效 0/196 查询被重排而长期无人察觉，此指标是该类事故的告警锚点

本地回退 `CrossEncoderReranker`（Bi-Encoder 近似，N+1 embedding）：仅 `retrieval.rerank.provider` 切本地时启用。

### 实测口径（替代早期未溯源数字）

| 语料 | 结论 | 报告 |
|---|---|---|
| PubMedQA（英文医学，n=196） | hybrid ≈ vector（Hit@10 100% 召回天花板）；rerank 无增益（当时 key 失效静默回退） | [retrieval-eval-pubmedqa](../testing/retrieval-eval-pubmedqa-2026-09-21.md) |
| CRUD-RAG（中文新闻，n=2394×3） | **CrossEncoder 精排真实增益**（Hit@3 +2.6pp，代价 +0.5s）；纯 RRF 在中文新闻不占优（hybrid vs vector Hit@5 -0.9pp） | [crud-rag-benchmark](../testing/crud-rag-benchmark-2026-09-22.md) |

即"混合+精排"的收益**依赖语料**，没有普适百分比。

### 层级检索（hierarchical / SWA）——2026-09-24 断链修复后真实生效

`HierarchicalChunkStrategy`（分块侧）：句子按 `。！？；` 切分 → 聚合为 parent（512 token）→ 每句单独成 leaf chunk，metadata 携带 `parentChunkId/position/siblingCount/windowContent(前后各 3 句)/chunkLevel`。

`HierarchicalRetrievalService`（检索侧）：
1. Sentence 召回 hybridSearch(topK×3)——**metadata 全链透传**（Milvus schema 新增 metadata VarChar 字段存 JSON、search outputFields 带出；ES 动态字段带回；`RetrievalResult` record 含 metadata 字段）
2. **Auto-Merging**：hitRatio ≥ `retrieval.swa.merging-ratio`(0.5) 且命中 ≥2 → 叶子拼接替换为父块
3. **Sentence Window**：保留叶子用 windowContent 替换内容（原内容存 metadata）

> 修复前该特性是端到端死代码（metadata 在检索边界丢失，全部候选落 orphans 分支）；现有 `HierarchicalRetrievalServiceTest` 四场景回归锁定。升级注意：旧集合启动时自动重建，需重灌。

---

## 四、多层级会话记忆

`MemoryService`（详见总览第六节）。核心机制：**Redis 热窗口 + MySQL 持久化 + 水位线对账 + 滚动摘要 + token 预算**。

三个值得知道的工程细节：
1. **先 MySQL 后 Redis 的写序**：自增 id 是摘要水位线的语义基础，全程持会话写锁保证 id 序 == 推入序
2. **按值 LREM 而非 LTRIM 删已摘要消息**：leftPush 重建会造成索引错位，LTRIM 会切错段（soak 测试 S1 抓出的并发缺陷）
3. **t_message.sources 持久化**（2026-09-24 新增）：assistant 消息携带引用 JSON 落库，历史接口 Redis 快路径按 id 批量补齐——刷新/回看会话引用不丢

质量保障：19/19 测试通过（契约 T1-T7 + 500 次随机化 soak），详见 [记忆系统测试报告](../testing/记忆系统测试报告.md)。

---

## 五、ReAct 推理引擎

完整设计与实现对照见 [03-react-engine](./03-react-engine.md)。一句话：**复杂度路由 + 思考→行动→观察循环 + 四重护栏（SQL 只读/循环检测/缓存 TTL/降级回退）**，`react.enabled` 默认关闭、当前 config.yaml 开启。

---

## 六、RAGAS 评测体系

`RAGASEvaluator`（项目自带简化版，非官方库）：faithfulness / answer relevancy / context precision / context recall 四指标，约 8 次 LLM + 4 次 embedding 判分。

- 调用方式：`POST /api/v1/evaluation/ragas`（手动）；批量走 `stress-test/eval_ragas_pipeline.py`（生成并发 4 / 判分并发 3）
- **无自动触发**（定时/on_kb_update/告警均为规划，见总览第十三节）
- 实测：综合 0.785（n=20），Context Recall 0.982 最强 / Context Precision 0.640 最弱（结构性原因：PubMedQA 摘要切 4-5 块，top-5 必混邻块），见 [ragas-eval](../testing/ragas-eval-2026-09-22.md)
- 已声明的方法学局限：判分与生成同源模型（自评偏置）、逐块二值判定不含位置折扣

---

## 七、分布式限流（已实现，待接入）

`DistributedRateLimiter`：Redis ZSET 滑动窗口 + Lua 原子脚本（ZREMRANGEBYSCORE 清窗外 → ZCARD 计数 → 未超限 ZADD+EXPIRE），三组配置（endpoint+IP 60/min、user 100/min、KB 自定义），Redis 故障 fail-open。

**当前状态：全工程零调用**——README 的"分布式限流"未生效，SSE 排队推送（依赖限流队列）一并未落地。接入方式与设计示例见 known-gaps，属总览第十三节规划项。保留此节是说明实现已就绪、接入成本低。

---

## 全链路总结

```
文档上传（Kafka 异步流水线）→ 知识库就绪
用户提问 → 认证(JWT) → [流式] 记忆 → 统一检索管线(消解→词映射→扩展→检索→预算) → LLM 流式生成 → 引用溯源(sources 持久化)
                      → [同步] 复杂度路由 ─ SIMPLE: 意图→检索管线
                                             └ COMPLEX: ReAct(护栏) → 降级则回退真实 RAG
质量闭环：RAGAS 手动评测 + Traces 步骤级追踪 + rerank/kafka/记忆 指标
系统保护：限流（已实现待接入）
```
