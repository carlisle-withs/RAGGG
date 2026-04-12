# ReAct 执行引擎方案

## 一、背景与目标

### 1.1 问题描述

当前 RAG 系统在处理复杂 query 时存在以下问题：
- 意图识别无法处理**多意图依赖**场景
- 缺乏**多步骤推理**能力
- 无法在多个知识源之间进行**智能路由**

### 1.2 目标

引入 **ReAct (Reasoning + Acting)** 执行引擎，实现：
- **任务复杂度路由**：简单任务走原有流程，复杂任务走 ReAct
- **多知识源检索**：支持知识库、数据库、对话历史的智能检索
- **缓存复用**：避免重复 API 调用
- **死循环防护**：检测并处理推理死循环
- **优雅降级**：复杂任务失败后降级到简单 RAG

---

## 二、整体架构

### 2.1 Query 处理流程

```
User Query
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 1. Memory Context Loading                                    │
│    MemoryService.buildContextPrompt()                        │
│    - 加载对话摘要                                            │
│    - 加载最近 N 条消息                                       │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Task Complexity Router (复杂度路由)                       │
│    ┌────────────────────────────────────────┐              │
│    │  轻量 LLM (Qwen2-1.5B-INT4, <100ms)   │              │
│    │  判断: SIMPLE / COMPLEX                │              │
│    └────────────────────────────────────────┘              │
│                                                            │
│    ┌──────────────────┬───────────────────────┐           │
│    │  SIMPLE          │  COMPLEX               │           │
│    │  原有 RAG 流程    │  ReAct Engine          │           │
│    └──────────────────┴───────────────────────┘           │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 3a. 简单流程 (Original RAG)                                 │
│     IntentClassifier → HybridRetrieval → Generate            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 3b. 复杂流程 (ReAct Engine)                                 │
│     ┌────────────────────────────────────────┐              │
│     │  ReAct Loop (最多 5 次迭代)            │              │
│     │  Reasoning → Action → Observation      │              │
│     │         ↓                              │              │
│     │  Loop Detection                        │              │
│     │  (死循环 → 降级 Simple RAG)            │              │
│     └────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. Response & Memory Update                                 │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 ReAct 执行循环

```
┌─────────────────────────────────────────────────────────┐
│                    ReAct Loop                             │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────┐    ┌──────────┐    ┌──────────────┐      │
│  │ Reasoning │ →  │  Action   │ →  │ Observation   │      │
│  │ (思考)    │    │ (执行)    │    │ (观察结果)    │      │
│  └──────────┘    └──────────┘    └──────────────┘      │
│         ↑                                        │       │
│         └────────────────────────────────────────┘       │
│                      Loop Check                          │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Cache Check (Embedding 相似度)                   │   │
│  │  - > 0.95: 直接返回缓存 Observation              │   │
│  │  - 0.85~0.95: 小模型精审                         │   │
│  │  - < 0.85: 正常执行                              │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Loop Detection (死循环检测)                      │   │
│  │  - 噪声剔除 → 事实提炼 → Hash 指纹               │   │
│  │  - 连续 3 次相同指纹 → 降级 Simple RAG          │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 三、核心模块设计

### 3.1 Task Complexity Router (复杂度路由)

**职责**：判断任务是简单还是复杂

| 模型 | Qwen2-1.5B-INT4 |
|------|------------------|
| 参数量 | 1.5B |
| 量化 | INT4 |
| 延迟目标 | < 100ms |
| 显存需求 | ~1.5GB |

**路由 Prompt**：
```
判断以下问题是否为复杂任务（需要多步推理或多个知识源）。

复杂任务的特征：
- 需要多步推理（先...再...然后...）
- 需要多个知识源（既查...又查...）
- 存在依赖关系（...之后才能...）
- 涉及多个实体或需要关联分析

简单任务的特征：
- 单意图
- 无依赖关系
- 单一知识源

问题：{user_query}

返回格式：{"complex": true/false, "reason": "简短原因"}
```

**分类标准**：

| 维度 | SIMPLE | COMPLEX |
|------|--------|---------|
| 意图数量 | 单意图 | 多意图 |
| 依赖关系 | 无 | 存在 |
| 知识源 | 单来源 | 多来源 |
| 实体数量 | ≤1 | ≥2 |

### 3.2 Action Space (行动空间)

| Action | 执行器 | 说明 |
|--------|--------|------|
| `retrieve_knowledge` | HybridRetrievalService | 知识库检索 (Milvus + ES) |
| `query_database` | MySQL | SQL 数据库查询 |
| `check_conversation_history` | MemoryService | 对话历史查询 |
| `final_answer` | LLM | 生成最终答案 |

**Action 选择逻辑**：
- LLM 根据当前上下文自主决定执行哪个 Action
- 不预设策略顺序

### 3.3 缓存分层策略

**目的**：避免重复 API 调用

**分层设计**：

| 相似度 | 处理方式 | 说明 |
|--------|---------|------|
| > 0.95 | Embedding 直接命中 | 快速返回缓存 |
| 0.85 ~ 0.95 | 轻量 LLM 精审 | 判断语义是否真正重复 |
| < 0.85 | 不命中 | 正常执行 |

**缓存 Key**：Action + 参数组合的 Embedding 向量

**缓存 Value**：Observation 结果

### 3.4 死循环检测

**目的**：防止 ReAct 循环无限执行

**检测流程**：

```
Action + Observation
    ↓
正则剔除噪声 (时间戳、随机ID等)
    ↓
Qwen2-1.5B 提炼核心事实
    ↓
计算 Hash 指纹
    ↓
与历史指纹比对
    ↓
连续 3 次相同 → 判定死循环 → 降级 Simple RAG
```

**事实提炼 Prompt**：
```
从以下文本中提取核心事实，剔除时间戳、ID、随机数等噪声。

格式：["事实1", "事实2", ...]

文本：
Action: {action}
Observation: {observation}
```

### 3.5 降级策略

**触发条件**：
- 死循环检测触发
- 达到最大迭代次数 (5 次)
- 执行异常

**降级方式**：放弃 ReAct，降级到简单 RAG 流程

---

## 四、配置设计

### 4.1 config.yaml

```yaml
# ReAct 执行引擎配置
react:
  enabled: true
  
  # 复杂度路由
  router:
    enabled: true
    model: Qwen2-1.5B
    timeout-ms: 100
  
  # Action Space
  actions:
    knowledge:
      enabled: true
      top-k: 5
    database:
      enabled: true
    conversation:
      enabled: true
      window-size: 10
  
  # 缓存配置
  cache:
    enabled: true
    similarity-threshold:
      direct-hit: 0.95
      review: 0.85
  
  # 死循环检测
  loop-detection:
    enabled: true
    max-iterations: 5
    fingerprint-match-count: 3
  
  # 小模型配置
  lightweight-llm:
    model: Qwen2-1.5B
    provider: local  # local / openai / siliconflow
    base-url: http://localhost:8081/v1
    api-key: dummy
```

---

## 五、类设计

### 5.1 包结构

```
src/main/java/com/rag/
├── application/
│   └── chat/
│       ├── ChatApplicationService.java    # 核心编排服务
│       ├── ComplexityRouter.java          # 复杂度路由
│       └── react/                         # 新增 ReAct 模块
│           ├── ReActEngine.java           # 执行引擎
│           ├── ReActContext.java          # 执行上下文
│           ├── ActionExecutor.java        # Action 执行器
│           ├── ActionCache.java            # 缓存管理
│           ├── LoopDetector.java           # 死循环检测
│           └── model/
│               ├── Thought.java           # 思考结果
│               ├── Action.java            # Action 定义
│               ├── ActionResult.java      # Action 执行结果
│               └── LoopDetectionResult.java
├── infrastructure/
│   ├── llm/
│   │   ├── ChatModelService.java
│   │   └── LocalLlmClient.java           # 本地 LLM 调用
│   └── retrieval/
│       └── HybridRetrievalService.java
```

### 5.2 核心类说明

#### ComplexityRouter
- 输入：query + context
- 输出：TaskComplexity (SIMPLE / COMPLEX)
- 延迟目标：< 100ms

#### ReActEngine
- 输入：query + context
- 输出：Answer 或降级到 Simple RAG
- 核心方法：execute()

#### ActionExecutor
- 管理 Action Space
- 执行具体的 Action (retrieve_knowledge / query_database / check_conversation_history)

#### ActionCache
- 缓存管理
- Embedding 相似度匹配
- 小模型精审

#### LoopDetector
- 噪声剔除
- 事实提炼 (调用本地 LLM)
- Hash 指纹计算与比对

---

## 六、Prompt 设计

### 6.1 ReAct Reasoning Prompt

```
你是一个智能助手，正在通过推理和行动来回答用户问题。

当前上下文：
- 用户问题：{query}
- 对话历史：{conversation_history}
- 已执行的Action和结果：{history}

可用Action：
1. retrieve_knowledge: 从知识库检索相关信息
2. query_database: 从数据库查询结构化数据
3. check_conversation_history: 查询对话历史
4. final_answer: 生成最终答案

请分析当前状态，决定下一步Action。

如果是最终答案，直接输出答案。
如果是需要更多信息，先思考(Reasoning)，然后选择一个Action。

输出格式：
Reasoning: 你的思考过程
Action: 选择的Action
Params: Action参数(如需要)
```

### 6.2 小模型精审 Prompt

```
判断以下两个查询是否语义重复。

查询A: {query_a}
查询B: {query_b}

语义重复的定义：
- 询问的是同一事物
- 查询意图相同
- 参数差异不影响核心语义

返回格式：{"duplicate": true/false, "reason": "简短原因"}
```

---

## 七、流程图

### 7.1 完整 Query 流程

```
User Query
    │
    ▼
┌─────────────────┐
│ Memory Context  │
└─────────────────┘
    │
    ▼
┌─────────────────┐
│ Complexity Router│
│ (Qwen2-1.5B)    │
└─────────────────┘
    │
    ├── SIMPLE ──────────────────────────────┐
    │                                        │
    ▼                                        ▼
┌─────────────────┐              ┌─────────────────┐
│ Simple RAG Flow │              │  ReAct Engine    │
│ IntentClassify  │              │                  │
│ HybridRetrieval  │              │ Loop:            │
│ Generate        │              │   Reasoning       │
└─────────────────┘              │   Action         │
    │                            │   Observation    │
    │                            │   Loop Check     │
    │                            │   Cache Check    │
    │                            └─────────────────┘
    │                                        │
    └────────────┬─────────────────────────┘
                 ▼
        ┌─────────────────┐
        │ Response        │
        │ + Memory Update │
        └─────────────────┘
```

### 7.2 ReAct 循环流程

```
Start Loop (max 5 iterations)
    │
    ▼
┌───────────────────┐
│ 1. Reasoning      │
│    LLM 分析现状   │
│    决定下一步Action│
└───────────────────┘
    │
    ▼
┌───────────────────┐
│ 2. Cache Check    │
│    Embedding 匹配  │
│    >0.95 → 用缓存  │
└───────────────────┘
    │
    ▼
┌───────────────────┐
│ 3. Execute Action │
│    知识库/数据库/  │
│    对话历史       │
└───────────────────┘
    │
    ▼
┌───────────────────┐
│ 4. Observation    │
│    存储结果       │
│    更新上下文     │
└───────────────────┘
    │
    ▼
┌───────────────────┐
│ 5. Loop Detection │
│    事实提炼       │
│    Hash 指纹      │
│    连续3次相同?   │
│    → 降级 Simple  │
└───────────────────┘
    │
    ▼
┌───────────────────┐
│ 6. Done?          │
│    final_answer?  │
│    No → 继续循环   │
│    Yes → 输出答案  │
└───────────────────┘
```

---

## 八、依赖模型

### 8.1 轻量 LLM

| 模型 | 参数量 | 量化 | 显存 | 用途 |
|------|--------|------|------|------|
| Qwen2-1.5B | 1.5B | INT4 | ~1.5GB | 复杂度路由 + 事实提炼 |
| Qwen2-0.5B | 0.5B | INT4 | ~500MB | 备选 |

### 8.2 本地部署方式

使用 Ollama 或 vLLM 部署：
```bash
# Ollama
ollama run qwen2:1.5b

# vLLM
python -m vllm.entrypoints.openai.api_server --model Qwen/Qwen2-1.5B
```

---

## 九、错误处理

| 场景 | 处理方式 |
|------|---------|
| 本地 LLM 不可用 | 降级到 Simple RAG |
| 知识库检索失败 | 尝试数据库检索 |
| 数据库查询失败 | 尝试对话历史 |
| 所有 Action 失败 | 降级到 Simple RAG |
| 死循环检测触发 | 降级到 Simple RAG |
| 最大迭代次数到达 | 降级到 Simple RAG |

---

## 十、监控指标

| 指标 | 说明 |
|------|------|
| `react.loop.detection.count` | 死循环检测触发次数 |
| `react.cache.hit.rate` | 缓存命中率 |
| `react.cache.精审.triggered` | 小模型精审触发次数 |
| `react.degradation.count` | 降级到 Simple RAG 次数 |
| `react.iteration.avg` | 平均迭代次数 |

---

## 十一、实施计划

| 阶段 | 任务 | 工期 |
|------|------|------|
| 1 | 创建包结构和核心类 | 1天 |
| 2 | 实现 ComplexityRouter | 0.5天 |
| 3 | 实现 ActionExecutor | 1天 |
| 4 | 实现 ActionCache | 0.5天 |
| 5 | 实现 LoopDetector | 0.5天 |
| 6 | 实现 ReActEngine | 1天 |
| 7 | 集成到 ChatApplicationService | 1天 |
| 8 | 配置与开关 | 0.5天 |
| 9 | 单元测试 | 1天 |
| 10 | 集成测试 | 1天 |
| **合计** | | **8天** |

---

## 十二、附录

### 12.1 术语表

| 术语 | 说明 |
|------|------|
| ReAct | Reasoning + Acting，一种结合推理和执行的 AI 范式 |
| Embedding | 文本向量化表示 |
| Hash 指纹 | 对核心事实计算的唯一标识 |
| 降级 | 从复杂流程退回到简单流程 |

### 12.2 参考资料

- [ReAct: Synergizing Reasoning and Acting in Language Models](https://arxiv.org/abs/2210.03629)
- [LangChain Agent](https://python.langchain.com/docs/modules/agents/)
- [LlamaIndex Agent](https://docs.llamaindex.ai/en/latest/module_guides/deploying/agents/)
