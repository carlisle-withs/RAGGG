# 文档目录

RAGGG 项目技术文档索引。**最后全面对账：2026-09-24**（与当日代码走读核对，各文档头部均有状态标注）。

## 阅读指引

- **想了解系统能做什么** → 根 [README.md](../README.md)（最新）→ [02-core-components](architecture/02-core-components.md)
- **想了解系统"实际"是什么样的（含缺陷）** → [known-gaps.md](./known-gaps.md) ⭐ 对账基准
- **想跑起来** → [01-quick-start](guides/01-quick-start.md)（2026-09-24 重写，可执行）
- **想看评测数据** → [testing/](./testing/) 各带日期报告

---

## 目录结构

```
docs/
├── README.md                              # 本索引
├── known-gaps.md                          # ⭐ 实现差距清单（宣称 vs 实际的对账基准）
├── knowledge-paradigms-comparison-2026-09-24.md   # RAG / LLM Wiki / Agentic Search 三范式对比
│
├── architecture/                          # 架构文档（2026-09-24 对账，含实现状态标注）
│   ├── 01-architecture-overview.md        # 系统架构总览（设计+规划混合，注意头部状态说明）
│   ├── 02-core-components.md              # 核心模块详解（Kafka/混合检索/记忆/RAGAS/限流）
│   └── 03-react-engine.md                 # ReAct 引擎（已实现，头部有实现差异回写）
│
├── database/                              # 数据库文档
│   ├── 01-schema.sql                      # 22 表完整参考 DDL（非实际建库来源，头部有说明）
│   ├── 02-database-design.md              # 逐字段设计说明 + P1 增量表补录
│   ├── 03-er-diagram.md                   # ER 关系图
│   └── 23-hierarchical-chunks.sql         # P1 增量 3 表（零引用死表，头部有警示）
│
├── guides/                                # 开发运维指南（2026-09-24 重写）
│   ├── 01-quick-start.md                  # 快速入门
│   └── 02-deployment.md                   # 部署文档
│
└── testing/                               # 评测与测试（实测报告 + 方案 + 历史报告）
    ├── retrieval-eval-pubmedqa-2026-09-21.md    # PubMedQA 召回评测
    ├── kafka-pipeline-throughput-report-2026-09-21.md  # 摄入吞吐与瓶颈下钻
    ├── crud-rag-benchmark-2026-09-22.md         # CRUD-RAG 中文基准
    ├── ragas-eval-2026-09-22.md                 # RAGAS 四指标评测
    ├── 记忆系统测试报告.md                        # 记忆系统契约+Soak 测试（发现 P0 摘要持久化 bug）
    ├── multimodal-experiment-report.md          # 多模态 RAG 实验
    ├── 压力测试报告.md                            # 早期上传链路压测（历史归档，头部有时效说明）
    └── 检索与生成质量压测方案.md                   # 未执行的测试方案（模板数字非实测）
```

## 文档索引

### 总览与对账

| 文档 | 状态 | 说明 |
|------|------|------|
| [known-gaps](./known-gaps.md) | ✅ 现状 | 实现差距清单：SWA 失效/安全形态/ReAct SQL/死表/溯源断链等，含修复优先级 |
| [knowledge-paradigms-comparison](./knowledge-paradigms-comparison-2026-09-24.md) | ✅ 分析 | RAG vs LLM Wiki vs Agentic Search 三范式优劣与混合演进路线 |

### 架构文档

| 文档 | 状态 | 说明 |
|------|------|------|
| [01-architecture-overview](architecture/01-architecture-overview.md) | ⚠️ 设计+规划混合 | 分层架构/网关/意图/记忆/Kafka/存储/安全；认证已收紧，限流/SSE Hub/RBAC 三实体仍为未实现设计，各节已加 ✅/⚠️/🚫 标注 |
| [02-core-components](architecture/02-core-components.md) | ⚠️ 大体准确 | 六大核心贡献详解；限流已实现未接线、重排已换代，节内有对账注记 |
| [03-react-engine](architecture/03-react-engine.md) | ⚠️ 已实现有偏差 | ReAct 引擎设计文档；机制与代码一致，模型选型/包结构/监控三处不同步（头部已回写） |

### 数据库文档

| 文档 | 状态 | 说明 |
|------|------|------|
| [01-schema.sql](database/01-schema.sql) | ⚠️ 参考 DDL | 22 表完整建表脚本；实际建库走 Hibernate ddl-auto，头部有活表/死表说明 |
| [02-database-design](database/02-database-design.md) | ✅ 已对账 | 逐字段设计 + P1 增量 3 表补录 + 表状态 |
| [03-er-diagram](database/03-er-diagram.md) | ⚠️ 结构图 | 22 表 ER 图（不含 P1 3 表；trace 亮点运行时不存在） |
| [23-hierarchical-chunks.sql](database/23-hierarchical-chunks.sql) | 🚫 未接线 | 层级分块关系表，零引用死表 |

### 开发指南

| 文档 | 状态 | 说明 |
|------|------|------|
| [01-quick-start](guides/01-quick-start.md) | ✅ 2026-09-24 重写 | 端口/config 机制/Ollama/API 路径均与代码核对，可执行 |
| [02-deployment](guides/02-deployment.md) | ✅ 2026-09-24 重写 | 引用仓库真实 compose，含水平扩容限制与性能基线 |

### 评测与测试（testing/）

| 文档 | 状态 | 说明 |
|------|------|------|
| [retrieval-eval-pubmedqa-2026-09-21](testing/retrieval-eval-pubmedqa-2026-09-21.md) | 📊 实测 | Hit@10=100%，MRR=0.938；发现 rerank 静默失效（0/196） |
| [kafka-pipeline-throughput-report-2026-09-21](testing/kafka-pipeline-throughput-report-2026-09-21.md) | 📊 实测 | 16.5 docs/min，95.1% 瓶颈在本地 embedding |
| [crud-rag-benchmark-2026-09-22](testing/crud-rag-benchmark-2026-09-22.md) | 📊 实测 | 中文基准：hybrid+rerank Hit@5=73.5%，MRR=67.5% |
| [ragas-eval-2026-09-22](testing/ragas-eval-2026-09-22.md) | 📊 实测 | RAGAS 综合 0.785，Context Precision 0.640 最弱 |
| [记忆系统测试报告](testing/记忆系统测试报告.md) | 📊 实测 | 19/19 通过；发现并修复 ConversationSummary 主键 P0 bug |
| [multimodal-experiment-report](testing/multimodal-experiment-report.md) | 📊 实验 | 多模态 RAG（4/5 查询 Top1 命中图片 chunk） |
| [压力测试报告](testing/压力测试报告.md) | 📄 历史归档 | 早期上传压测（批量 embedding 33× 提升等），头部有时效说明 |
| [检索与生成质量压测方案](testing/检索与生成质量压测方案.md) | 📋 未执行方案 | 模板数字非实测；被测接口口径已过时（头部有纠正） |

---

*最后更新: 2026-09-24（全量对账：guides 重写、architecture/database 补状态标注、评测报告归入 testing/、新增 known-gaps 与三范式对比）*
