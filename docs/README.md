# 文档目录

RAGGG 项目技术文档索引。**最后全面对账：2026-09-24**（与当日代码走读核对；覆盖度补齐后共 26 个文件）。

## 阅读指引

- **想了解系统能做什么** → 根 [README.md](../README.md) → [02-core-components](architecture/02-核心模块详解.md)
- **想了解系统"实际"是什么样的（含缺陷）** → [实现差距清单.md](./实现差距清单.md) ⭐ 对账基准
- **想调接口** → [api-reference](./API接口参考.md)（全端点 + 流式协议）
- **想改配置** → [configuration-reference](./配置参考.md)（全配置键）
- **想跑起来** → [01-quick-start](guides/01-快速入门.md)；**日常运维/排障** → [03-operations](guides/03-运维手册.md)
- **想看评测数据** → [testing/](./testing/) 各带日期报告

---

## 目录结构

```
docs/
├── README.md                              # 本索引
├── 实现差距清单.md                          # ⭐ 实现差距清单（宣称 vs 实际的对账基准）
├── API接口参考.md                       # 全量 REST 接口参考（19 Controller / 60+ 端点 / NDJSON 协议）
├── 配置参考.md             # 配置参考全集（config.yaml + application.yml + react 段）
├── 知识范式对比-2026-09-24.md   # RAG / LLM Wiki / Agentic Search 三范式对比
│
├── architecture/                          # 架构文档（2026-09-24 重写为现状文档，与代码一致）
│   ├── 01-系统架构总览.md        # 系统架构总览（链路/流水线/安全/可观测/规划清单）
│   ├── 02-核心模块详解.md              # 七大核心模块详解（含实测数据与修复后状态）
│   ├── 03-ReAct推理引擎.md                 # ReAct 引擎设计与实现（含护栏/降级/缓存机制）
│   ├── 04-前端架构.md                     # 前端架构（路由/store/流式解析/引用面板/构建）
│   ├── 05-分块策略详解.md          # 五种分块策略详解（算法/参数/决策树/SWA 父子结构）
│   └── 06-多模态能力.md                   # 多模态能力（视觉对话/文档 OCR 增强/实验结论）
│
├── database/                              # 数据库文档
│   ├── 01-schema.sql                      # 22 表完整参考 DDL（非实际建库来源，头部有说明）
│   ├── 02-数据库设计.md              # 逐字段设计说明 + P1 增量表补录
│   ├── 03-ER关系图.md                   # ER 关系图
│   └── 23-hierarchical-chunks.sql         # P1 增量 3 表（零引用死表，头部有警示）
│
├── guides/                                # 开发运维指南（2026-09-24 重写）
│   ├── 01-快速入门.md                  # 快速入门
│   ├── 02-部署指南.md                   # 部署文档（水平扩容限制/性能基线）
│   └── 03-运维手册.md                   # 运维手册（test-agent/watchdog/stress-test/告警锚点/排障 runbook）
│
└── testing/                               # 评测与测试（实测报告 + 方案 + 历史报告）
    ├── 检索评测-PubMedQA-2026-09-21.md    # PubMedQA 召回评测
    ├── Kafka流水线吞吐报告-2026-09-21.md  # 摄入吞吐与瓶颈下钻
    ├── CRUD-RAG中文基准-2026-09-22.md         # CRUD-RAG 中文基准
    ├── RAGAS评测-2026-09-22.md                 # RAGAS 四指标评测
    ├── 记忆系统测试报告.md                        # 记忆系统契约+Soak 测试（发现 P0 摘要持久化 bug）
    ├── 多模态实验报告.md          # 多模态 RAG 实验
    ├── 压力测试报告.md                            # 早期上传链路压测（历史归档，头部有时效说明）
    └── 检索与生成质量压测方案.md                   # 未执行的测试方案（模板数字非实测）
```

## 文档索引

### 总览与参考

| 文档 | 状态 | 说明 |
|------|------|------|
| [known-gaps](./实现差距清单.md) | ✅ 现状 | 实现差距清单（含修复状态与优先级），对账基准 |
| [api-reference](./API接口参考.md) | ✅ 现状 | 全量接口：19 Controller / 认证与权限约定 / 流式 NDJSON 协议 / 词映射·意图树·traces 新 API 契约 |
| [configuration-reference](./配置参考.md) | ✅ 现状 | 全配置键（config.yaml / application.yml / react 段）+ 配置常见坑 |
| [knowledge-paradigms-comparison](./知识范式对比-2026-09-24.md) | ✅ 分析 | RAG vs LLM Wiki vs Agentic Search 三范式优劣与混合演进路线 |

### 架构文档

| 文档 | 状态 | 说明 |
|------|------|------|
| [01-architecture-overview](architecture/01-系统架构总览.md) | ✅ 现状文档 | 系统架构总览：真实调用链/流水线/检索/记忆/安全/可观测/部署；未实现项集中在第十三节规划清单 |
| [02-core-components](architecture/02-核心模块详解.md) | ✅ 现状文档 | 七大核心模块：Kafka 流水线/统一检索管线/混合检索+SWA/记忆/ReAct/RAGAS/限流（待接入） |
| [03-react-engine](architecture/03-ReAct推理引擎.md) | ✅ 现状文档 | ReAct 设计与实现：复杂度路由/动作空间与 SQL 护栏/缓存 TTL/循环检测/降级回退 |
| [04-frontend](architecture/04-前端架构.md) | ✅ 现状文档 | 前端架构：路由树/三 store/NDJSON 流式解析/引用面板/与后端契约对齐点 |
| [05-chunking-strategies](architecture/05-分块策略详解.md) | ✅ 现状文档 | 五种分块策略：算法细节/参数语义/intelligent 决策树/SWA 父子结构与数据通路 |
| [06-multimodal](architecture/06-多模态能力.md) | ✅ 现状文档 | 多模态：视觉对话端点/文档 OCR 增强链路/实验结论与当前边界 |

### 数据库文档

| 文档 | 状态 | 说明 |
|------|------|------|
| [01-schema.sql](database/01-schema.sql) | ⚠️ 参考 DDL | 22 表完整建表脚本；实际建库走 Hibernate ddl-auto，头部有活表/死表说明 |
| [02-database-design](database/02-数据库设计.md) | ✅ 已对账 | 逐字段设计 + P1 增量 3 表补录 + 表状态 |
| [03-er-diagram](database/03-ER关系图.md) | ⚠️ 结构图 | 22 表 ER 图（不含 P1 3 表；trace 亮点运行时不存在） |
| [23-hierarchical-chunks.sql](database/23-hierarchical-chunks.sql) | 🚫 未接线 | 层级分块关系表，零引用死表 |

### 开发与运维指南

| 文档 | 状态 | 说明 |
|------|------|------|
| [01-quick-start](guides/01-快速入门.md) | ✅ 现状 | 端口/config 机制/Ollama/认证流程均与代码核对，可执行 |
| [02-deployment](guides/02-部署指南.md) | ✅ 现状 | 引用仓库真实 compose，含水平扩容限制与性能基线 |
| [03-operations](guides/03-运维手册.md) | ✅ 现状 | 工具链（test-agent/watchdog/stress-test 入口）、Grafana 面板目录、三个告警锚点、四类故障排障 runbook |

### 评测与测试（testing/）

> ⚠️ **基线时效提示**：下列实测数字测于 2026-09-24 代码修复**之前**——SWA 修复/rerank 混排修复/词映射接入/上下文预算均可能改变结果，重大变更后建议重跑基线（见 [运维手册第四节](guides/03-运维手册.md)）。

| 文档 | 状态 | 说明 |
|------|------|------|
| [retrieval-eval-pubmedqa-2026-09-21](testing/检索评测-PubMedQA-2026-09-21.md) | 📊 实测 | Hit@10=100%，MRR=0.938；发现 rerank 静默失效（0/196） |
| [kafka-pipeline-throughput-report-2026-09-21](testing/Kafka流水线吞吐报告-2026-09-21.md) | 📊 实测 | 16.5 docs/min，95.1% 瓶颈在本地 embedding |
| [crud-rag-benchmark-2026-09-22](testing/CRUD-RAG中文基准-2026-09-22.md) | 📊 实测 | 中文基准：hybrid+rerank Hit@5=73.5%，MRR=67.5% |
| [ragas-eval-2026-09-22](testing/RAGAS评测-2026-09-22.md) | 📊 实测 | RAGAS 综合 0.785，Context Precision 0.640 最弱 |
| [记忆系统测试报告](testing/记忆系统测试报告.md) | 📊 实测 | 19/19 通过；发现并修复 ConversationSummary 主键 P0 bug |
| [multimodal-experiment-report](testing/多模态实验报告.md) | 📊 实验 | 多模态 RAG（4/5 查询 Top1 命中图片 chunk） |
| [压力测试报告](testing/压力测试报告.md) | 📄 历史归档 | 早期上传压测（批量 embedding 33× 提升等），头部有时效说明 |
| [检索与生成质量压测方案](testing/检索与生成质量压测方案.md) | 📋 未执行方案 | 模板数字非实测；被测接口口径已过时（头部有纠正） |

外部工具文档：[stress-test/README.md](../stress-test/README.md)（压测脚本使用说明）

---

*最后更新: 2026-09-24（覆盖度补齐：新增 API 参考/配置参考/前端/分块/多模态/运维手册六份 + 修复 config.yaml.example 过时 react 段）*
