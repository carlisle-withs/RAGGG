# CRUD-RAG 中文基准复现实验报告

- **日期**：2026-09-22
- **目的**：在当前代码与配置上复现简历中的 CRUD-RAG 检索指标（Hit@5 = 73%，MRR = 67.3%）
- **数据**：官方仓库 IAAR-Shanghai/CRUD_RAG 的 `split_merged.json`（25.6MB，经 GitHub Blobs API 下载）
  - QuestAnswer 三任务：1-doc 800 条 / 2-docs 797 条 / 3-docs 797 条，共 **2394 条 QA**
  - 语料：去重后 **2397 篇中文新闻**，经真实 Kafka 流水线灌入 KB 20
- **判定逻辑**：与原版 `benchmark_crudd.py` 完全一致（answer 实体词 × chunk 字符串包含）
- **脚本**：`stress-test/benchmark_crudd_local.py`（Linux 适配版，3 模式）
- **环境**：bge-m3 本地 Ollama embedding；rerank = SiliconFlow bge-reranker-v2-m3（有效 key）

## 1. 实验过程

| 阶段 | 结果 |
|---|---|
| 灌库 | 2397/2397 篇，**0 失败**，7349 chunks，11 分钟（≈216 docs/min，小文档） |
| 评测 | 2394 QA × 3 模式（vector / hybrid 纯RRF / hybrid+rerank），39 分钟 |

## 2. 实验结果

### 分任务

| 任务 | 模式 | Hit@3 | Hit@5 | Hit@10 | MRR | NDCG@5 |
|---|---|---|---|---|---|---|
| 1-doc | vector | 70.0 | 72.4 | 75.4 | 65.6 | 66.2 |
| 1-doc | hybrid | 68.9 | 71.8 | 75.6 | 65.1 | 65.8 |
| **1-doc** | **hybrid+rr** | **70.9** | **73.5** | **76.2** | **67.5** | **67.9** |
| 2-docs | vector | 60.4 | 69.4 | 76.0 | 55.3 | 57.6 |
| 2-docs | hybrid | 62.9 | 68.3 | 75.9 | 55.7 | 57.4 |
| 2-docs | hybrid+rr | 67.0 | 72.1 | 76.5 | 59.1 | 61.5 |
| 3-docs | vector | 66.2 | 74.2 | 80.7 | 58.3 | 61.1 |
| 3-docs | hybrid | 67.5 | 73.1 | 79.7 | 59.4 | 61.4 |
| 3-docs | hybrid+rr | 69.0 | 76.0 | 81.4 | 62.2 | 64.5 |

### 三任务平均

| 模式 | Hit@3 | Hit@5 | Hit@10 | MRR | NDCG@10 |
|---|---|---|---|---|---|
| vector | 65.5 | 72.0 | 77.4 | 59.8 | 62.4 |
| hybrid（纯RRF） | 66.4 | 71.1 | 77.1 | 60.1 | 62.3 |
| **hybrid+rerank** | **69.0** | **73.9** | **78.1** | **62.9** | **64.9** |

## 3. 与简历数字的对照

| 口径 | Hit@5 | MRR | 结论 |
|---|---|---|---|
| 简历声称 | 73% | 67.3% | — |
| **1-doc + hybrid+rerank（本实验）** | **73.5%** | **67.5%** | **精确复现**（差 0.5pp / 0.2pp） |
| 三任务平均 + hybrid+rerank | 73.9% | 62.9% | Hit@5 略超，MRR 低 4.4pp |

**判断**：简历的 73% / 67.3% 与「1-doc 任务 + 混合检索 + BGE 精排」口径精确吻合，数字可复现、可信。若按三任务平均口径，Hit@5 仍达标（73.9%），MRR 会低一些（62.9%）——面试被追问时建议主动说明口径（1-doc 任务、含二阶段精排）。

## 4. 技术发现

1. **精排是真实增益**：hybrid+rr 相对纯 hybrid，Hit@3 +2.6pp、MRR +2.8pp（平均），在 2-docs 任务上增益最大（Hit@3 +4.1pp）——CrossEncoder 对多文档证据合并排序的价值得到验证。代价约 +0.5s/查询（SiliconFlow API 往返）。
2. **纯 RRF 融合在中文新闻上不占优**：hybrid 相对 vector 在 Hit@5 上 -0.9pp（71.1 vs 72.0），仅 Hit@3/MRR 微升——中文分词场景 BM25 噪声较大，RRF 等权融合稀释了向量路的优势；该架构的价值主要通过"召回池扩大 + 精排"实现，而非 RRF 本身。
3. **任务难度排序**：2-docs 最难（MRR 55-59%，两篇证据文档分散排序位置），3-docs 的 Hit@5 反而最高（三篇证据提高 top-5 命中概率）。
4. 流水线在 2400 篇规模下零失败，小文档吞吐 216 docs/min（受本地 Ollama 嵌入限制，见 Kafka 流水线压测报告）。

## 5. 工件

| 文件 | 说明 |
|---|---|
| `stress-test/benchmark_crudd_local.py` | 复现脚本（可直接重跑） |
| `stress-test/data/crud_rag/split_merged.json` | 官方数据集（25.6MB） |
| `stress-test/data/crud_rag/crud_result_2026-09-22.json` | 本次全部结果 |
