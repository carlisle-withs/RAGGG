# 文档目录

本文档包含 RAGGG 项目的所有技术文档。

## 目录结构

```
docs/
├── README.md                           # 本文档
│
├── architecture/                       # 架构文档
│   ├── 01-architecture-overview.md    # 系统架构总览
│   └── 02-core-components.md         # 核心模块详解
│
├── database/                          # 数据库文档
│   ├── 01-schema.sql                  # SQL 建表脚本
│   ├── 02-database-design.md         # 数据库设计 (完整版)
│   └── 03-er-diagram.md              # ER 关系图
│
└── guides/                            # 开发运维指南
    ├── 01-quick-start.md              # 快速入门
    └── 02-deployment.md               # 部署文档
```

## 文档索引

### 架构文档

| 文档 | 说明 |
|------|------|
| [01-architecture-overview](architecture/01-architecture-overview.md) | 系统架构总览，包含技术选型、网关层、应用层、领域层设计 |
| [02-core-components](architecture/02-core-components.md) | 核心模块详解，包含 Kafka 流水线、意图识别、混合检索、会话记忆等 |

### 数据库文档

| 文档 | 说明 |
|------|------|
| [01-schema.sql](database/01-schema.sql) | MySQL 建表脚本，包含 22 张表 |
| [02-database-design](database/02-database-design.md) | 数据库详细设计，包含每张表的字段说明 |
| [03-er-diagram](database/03-er-diagram.md) | ER 关系图，展示表之间的关联 |

### 开发指南

| 文档 | 说明 |
|------|------|
| [01-quick-start](guides/01-quick-start.md) | 快速入门指南 |
| [02-deployment](guides/02-deployment.md) | 部署文档 |

---

*最后更新: 2026-04-10*
