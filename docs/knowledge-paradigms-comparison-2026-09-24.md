# 知识获取三范式深度对比：RAG / LLM Wiki / Agentic Search

- **日期**：2026-09-24
- **性质**：架构范式分析文档（非实验报告；引用数字均来自本仓库已有评测报告与代码走读，见文末"依据来源"）
- **对比对象**：
  - **RAG**——查询时一次性检索（本系统 RAGGG 为实例）
  - **LLM Wiki**——摄入时知识编译（据社区资料，与 Karpathy 提出的"知识编译"思路同源）
  - **Agentic Search**——查询时多轮迭代检索（以 Claude Code 的 grep 工作方式为代表；Anthropic 工程师已公开确认 Claude Code 不使用 RAG，无 embedding、无向量库）
- **📌 时效说明（同日更新）**：本文为 2026-09-24 的范式分析。文中引用的部分工程缺陷**当日已完成修复**（SWA metadata 断链已打通且 Auto-Merging 生效、`permitAll` 已收紧为强制认证、ReAct 遗留清零），演进路线 1（修 SWA）与路线 3 的词映射部分已执行——**范式层面的对比分析与结论不受影响**，工程现状以 [known-gaps](./known-gaps.md) 为准。
- **一句话结论**：三种范式不是互相淘汰的关系，而是**把智能与成本押在流水线不同位置**的三种下注——RAG 押在摄入时建索引（查询便宜）、LLM Wiki 押在摄入时编译知识（查询最便宜但摄入最贵）、Agentic Search 押在查询时多轮循环（摄入零成本但单查询最贵）。**语料类型决定范式成败**：有词法锚点和显式结构的语料（代码）适合 agentic，同义改写密集的自然语言语料（论文/新闻）必须靠 embedding，需要长期沉淀的团队知识适合编译。对本系统而言，三者都已各有胚胎（hybrid 检索 / 意图表+词映射 / ReAct 引擎），正确方向是补完而非替换。

---

## 1. 三种范式的工作机制

### 1.1 RAG：查询时一次性检索（本系统现状）

文档进库时解析（Tika）→ 分块（512 字符级）→ embedding → 双写 Milvus（HNSW 向量）+ ES（BM25 倒排）。查询时一次 embedding → 双路召回各 20 候选 → RRF 融合 → CrossEncoder 精排 → top-5 原文片段塞进 Prompt → 一次生成。

**特征**：索引是" dumb but complete"——不理解内容，但逐字保留了全部内容；每次查询只付一次检索 + 一次生成的钱。

### 1.2 LLM Wiki：摄入时知识编译

文档进库时就让 LLM 通读，把知识提炼、去重、关联成持续维护的结构化条目（概念页/实体页/交叉链接）。查询时回答的是这份沉淀后的知识，而非原文片段。

**特征**：索引是" smart but lossy"——LLM 在摄入时已完成理解和组织，但改写即压缩，原文细节不可逆地丢失。

### 1.3 Agentic Search：查询时多轮迭代检索（Claude Code 方式）

不建任何索引，原文放磁盘上。查询时由 LLM 拿着工具（grep/glob/read）做多轮循环：自己规划关键词 → grep → 阅读结果 → 决定读哪个文件 → 顺着 import/调用关系再 grep——直到拼出答案。Claude Code 团队起先用 RAG 路线（Voyage 等检索器索引整个代码库），最终放弃，回归 grep-and-read。

**特征**：没有索引这一层，"检索"本身就是一个由 LLM 驱动的调研过程；每次查询消耗数十次工具调用、数万 token 量级的上下文。

### 1.4 核心框架：成本押在哪个位置

| | RAGGG（RAG） | LLM Wiki | Agentic Search |
|---|---|---|---|
| **摄入时** | 低：一次 embedding（本系统流水线 95.1% 耗时已在此，可扩容） | 高：LLM 精读每篇，估算为本系统的 5-50 倍 | **零**：原文放那儿就行 |
| **查询时** | 低：1 次 embedding + 检索 + 1 次生成 | 最低：读沉淀好的页面 | **最高：一个 query = 一场调研**（数十次模型调用、分钟级延迟） |
| **知识形态** | 原文 chunk，逐字保留 | LLM 改写的结构化条目，有损 | 原文本身，无索引中间层 |
| **时效性** | 重灌流水线，分钟级 | 重编译，小时级 | **实时**：读的就是磁盘上的最新文件 |
| **通俗比喻** | 开卷考试：临时翻书找那几页 | 先请人把书读薄做成笔记，考试看笔记 | 请一个研究员每次现场调研，但从不做笔记 |

---

## 2. 本系统（RAGGG）已验证的能力边界

| 能力 | 实测结果 | 来源报告 |
|---|---|---|
| 精确召回（PubMedQA，196 queries） | Hit@3/5/10 = 99.0/99.5/100%，MRR@10 = 0.938，检索延迟 0.192s | retrieval-eval-pubmedqa-2026-09-21 |
| 中文基准（CRUD-RAG，2394 QA × 3 任务） | 1-doc + hybrid + rerank：Hit@5 = 73.5%，MRR = 67.5% | crud-rag-benchmark-2026-09-22 |
| 生成质量（RAGAS，n=20） | Context Recall 0.982 / Faithfulness 0.926（剔异常）/ **Context Precision 0.640（最弱）** / Answer Relevancy 0.687 | ragas-eval-2026-09-22 |
| 摄入吞吐（50 docs × 64KB） | 16.5 docs/min = 890 chunks/min，零失败；95.1% 耗时在本地 embedding | kafka-pipeline-throughput-report-2026-09-21 |
| 记忆系统 | 19/19 测试通过（含 500 次随机化 soak）；token 预算硬上界 | testing/记忆系统测试报告.md |

---

## 3. RAGGG（RAG 范式）的优势与缺陷

### 3.1 优势

**R1：无损精确召回，且已被自家基准验证。** 两大基准的命中判定都在考验这一点：PubMedQA 的 golden 是 `all_relevant_sentence_keys`（标准答案即语料原句），Hit@10 = 100% 只有在知识逐字保留的前提下才可能；CRUD-RAG 的 content_hit 是"实体 + 数字 + 中文短语在 chunk 中字符串包含"。LLM Wiki 在这两个口径下直接失分（编译改写后原句不复存在）；Agentic Search 无损但受制于词法锚点（见 5.2-G1）。

**R2：摄入成本低一个数量级以上，且可扩展。** 摄入链路 LLM 参与度为零。用本仓库实测换算 LLM Wiki 的量级：GLM-5.3-Flash 生成 p50 60s/次 → PubMedQA 196 篇从 72 秒变 ~3.3 小时，CRUD-RAG 2397 篇从 11 分钟变 ~40 小时（并发 4 约 10 小时）。Agentic Search 摄入为零，但把成本全部转嫁到每次查询（见 5.2-G2）。

**R3：更新与删除语义简单。** "删文档 → 重灌"是文档级、确定性的操作。LLM Wiki 的更新是知识合并（定位条目、增量改写、冲突消解）；Agentic Search 天然最新（优势在它那边）。

**R4：溯源在原理上可行。** chunk 与源文档可逐字对齐，后端 `/api/v1/chat` 已返回 sources，RAGAS Faithfulness 0.926 正建立在这个可对齐性上。编译式条目无法回指原文位置，忠实度评估失去抓手。

**R5：查询延迟低、可承载高 QPS。** 0.192s 检索 + 秒级生成，索引成本已摊销，适合多用户并发服务。Agentic Search 单查询分钟级、成本线性无摊销，做不了高 QPS 服务。

**R6：规模化上限高。** Milvus HNSW 百万级 chunk 毫秒级检索；另两种范式分别在编译成本（LLM Wiki）和单查询成本（Agentic）上遇到规模墙。

### 3.2 缺陷

**范式固有（换实现也躲不掉）：**

- **F1：片段化天花板——综合理解弱。** RAGAS 最弱项 Context Precision 0.640：top-5 里平均仅 ~3.2 块相关。"多篇文献共同指向什么结论"需要跨文档知识关联，孤立片段在原理上给不出。PubMedQA 召回已 100% 封顶，边际收益归零，下一步价值只能在综合理解侧。
- **F2：知识零沉淀。** 每次查询从零检索，同样的问法重复付全价。唯一"沉淀"是 ReAct 的 ActionCache（进程内、无 TTL、无淘汰）。
- **F3：上下文成本随质量要求线性上升。** 塞更多/更大片段换理解力，token 成本与注意力稀释随之而来；检索上下文没有类似记忆系统 `context-max-tokens=3000` 的预算机制。
- **F4：索引会"说谎"。** 任何索引层都引入"写入的"与"读出的"不一致风险——本系统刚踩过镜像版的坑：SWA 分块时写入索引的 metadata（parentChunkId/windowContent），检索时拿不回来（Milvus outputFields 与 ES 返回均不携带），Auto-Merging 永不触发，核心特性成为死代码。Agentic Search 读源文件本身，从机制上不存在这类不一致。
- **F5：embedding 模型绑定。** 换 embedding 模型 = 全量重嵌入（本系统 Milvus 维度不匹配时甚至会 drop 重建、静默丢数据）。

**工程实现（可修，不构成换范式的理由）：**

| 编号 | 问题 | 影响 |
|---|---|---|
| E1 | 层级检索（SWA）端到端失效（F4 的实例） | 本用于缓解 F1/F3 的特性是死代码 |
| E2 | rerank 静默回退：SiliconFlow key 401 后返回原序不报错（0/196 排序被改变） | 精排可靠性无告警 |
| E3 | 溯源断链：流式接口不返回 sources，前端无引用 UI | R4 只存在于后端接口层 |
| E4 | 语义分块名不副实（规则启发式，非 embedding 相似度） | 分块质量低于宣称 |
| E5 | 摄入可靠性弱：无重试/DLT、producer 静默丢消息、`this.doProcess()` 自调用致事务失效 | 状态与实际索引可能不符 |
| E6 | 本地 Bi-Encoder 回退 N+1 次 embedding；RRF 分与 relevance 分混排路径 | 回退路径性能与正确性双坑 |
| E7 | 安全配置演示形态（permitAll 等）、限流器零接入 | 上生产前必须修 |

---

## 4. LLM Wiki（知识编译范式）的优势与缺陷

### 4.1 优势

- **W1：知识质量——提炼、去噪、关联。** 把同一概念的分散表述归并成条目、摄入时过滤噪声、显式建交叉链接。直接命中 F1：综合类问题回答的是被组织过的知识。
- **W2：查询侧上下文效率高。** 知识已压缩组织，同信息量占更少 token，缓解注意力稀释（F3）。
- **W3：知识有积累。** 语料级沉淀（F2 的解药）：第 1001 篇文档进来时系统"已经懂"前 1000 篇。
- **W4：术语一致性。** 编译时统一术语（"向量库"→"向量数据库"），对检索和生成都是免费的质量提升。（本仓库 `t_query_term_mapping` 表正是这个思路的人工版半成品。）

### 4.2 缺陷

- **L1：有损压缩不可逆（最根本）。** 丢什么信息由编译模型在摄入时决定，而问题分布是查询时才知道的——今天被归纳掉的一个数字，可能正是明天某条查询的答案。本系统两大基准的逐字匹配口径在此直接失效。
- **L2：编译与更新成本高。** 摄入 5-50 倍（R2 的换算）；更新需要合并语义，运维复杂度远高于重灌。
- **L3：编译幻觉被沉淀和放大。** 改写时的一处事实错误成为知识库"正式内容"，后续所有命中该条目的查询反复引用——错误从一次性事件变成持久污染。RAG 幻觉发生在生成时、不落库。
- **L4：溯源与可评估性丢失。** 条目无法回指原文；RAGAS/Recall 评测闭环建立在"上下文是原文 chunk"之上，编译式需另建更贵更主观的评测。
- **L5：增量一致性是社区未解难题。** 后入文档与前入矛盾时覆盖/并存/标注冲突，本质是把数据库并发控制交给概率性 LLM。
- **L6：冷启动与故障恢复贵。** "换模型重灌"从 72 秒变小时级工程。

### 4.3 小结

LLM Wiki 的每个优势，本系统都能用更低成本部分获得：W1/W2 → 修 SWA + 摘要层；W3 → 查询侧沉淀 + 文档级摘要；W4 → `t_query_term_mapping` 落地。反方向不成立：编译式要获得 RAG 的无损性只能保留原文索引——等于在编译层之下又建了一个 RAG。

---

## 5. Agentic Search（Claude Code 方式）的优势与缺陷

### 5.1 优势

- **A1：零摄入成本、零索引维护。** 不建索引就没有索引构建/运维成本，没有"索引过期"问题。Claude Code 团队放弃 RAG 路线的直接原因之一：索引在发布前就已过时；grep-and-read 搜的永远是最新代码。本系统的对照痛点：Milvus 维度变更即 drop 重建（F5）。
- **A2：零陈旧性 = 索引永远不说谎。** 读的是源文件本身，"写入的字段"与"读出的字段"不可能不一致。对照本系统 SWA 断链（F4/E1）：索引承诺了检索兑现不了的东西——这类 bug 在 agentic 架构里没有存在的前提。
- **A3：多跳/综合问题强——导航式而非召回式。** 回答"X 是怎么工作的"靠顺着 import/调用链走，天然多跳；对照 RAG 的一次性 top-5 召回（F1）和 LLM Wiki 的静态条目（跨条目关联靠编译时建好）。且 agent 边读边验证，能自我纠错（grep 没中就换关键词）。
- **A4：无损与溯源。** 读的就是原文，与 RAG 同级（R1/R4）。
- **A5：成本结构与价值对齐。** 零摊销 + 按查询深挖付费，适合低频高价值查询（"每周问几次、每次值得一场调研"）；高频浅查询在这种模式下不经济。
- **A6：无 embedding 模型绑定。** 换 LLM 不需要重建任何东西（对照 F5）。

### 5.2 缺陷

- **G1：依赖词法锚点——自然语言语料上 grep 精度坍塌（对本系统是决定性的）。** agentic search 能打，靠的不是"LLM 搜索聪明"，而是**代码语料自带词法锚点**：函数名/类名/import 路径全局唯一，`grep -r "handleRetry"` 一发命中；文件树和调用链提供导航结构。自然语言文档（论文/新闻）同一概念一百种说法，查询与原文几乎无字面重叠——同义改写恰恰是 embedding 存在的理由。本仓库 `benchmark_chinese_query.py`（中文问英文库）就是现成反证。Milvus 团队《Why I'm Against Claude Code's Grep-Only Retrieval》反对的正是这点：超大库、语义模糊查询时纯 grep 不够。**"grep 回归"是代码语料的特解，不是 RAG 的死刑判决——语料决定范式。**
- **G2：单查询成本三种范式中最高。** 一个 query = 数十次工具调用 + 数万 token 上下文 + 分钟级延迟。以本仓库生成 p50 60s 计，一轮"规划→grep→读结果"就接近一次完整生成的成本，几十轮的量级可想而知。做面向多用户的问答服务，单位查询经济性无法接受。
- **G3：规模上限低。** 单查询是一场调研，语料越大每场调研越长；十万级文件已是实践舒适区上限。对照 RAG 百万级 chunk 毫秒检索（R6）。
- **G4：结果非确定性、评测困难。** 同一问题两次执行路径不同、答案可能不同；评测一次 = 完整跑一次 agent，无法像本系统那样批量跑 196/2394 条做 Recall/MRR。本仓库好不容易建立的评测闭环在此失效。
- **G5：需要工具执行环境与原文直通权限。** 每次查询都要 grep/读源文件（甚至跑代码），权限面和安全面远大于"查预索引的 chunk"；多租户场景下各 KB 的原文隔离要靠文件系统层做，远比 Milvus 的 kb_id 过滤（即便它现在是字符串拼接）难做对。
- **G6：无知识沉淀。** 与 RAG 同病（F2），甚至更彻底——连 embedding 索引这份"沉积物"都没有。

---

## 6. 三方正面对比矩阵

| 维度 | RAGGG（RAG） | LLM Wiki | Agentic Search | 优势方 |
|---|---|---|---|---|
| 摄入成本 | 低 | 高 5-50 倍 | 零 | Agentic |
| 单查询成本 | 低 | 最低 | 最高（数十次调用） | LLM Wiki |
| 查询延迟 | 秒级 | 秒级 | 分钟级 | RAG / LLM Wiki |
| 高 QPS 服务能力 | ✅ | ✅ | ❌ 成本线性无摊销 | RAG / LLM Wiki |
| 语料规模上限 | 百万级 chunk | 受编译成本限制 | ~十万级文件 | RAG |
| 语义同义改写 | ✅ embedding 强项 | ✅ | ❌ 依赖词法锚点 | RAG / LLM Wiki |
| 多跳/综合问题 | 弱（CP 0.640） | 强（编译时关联） | 强（导航式 + 自纠错） | LLM Wiki / Agentic |
| 无损性/溯源 | ✅ 逐字对齐 | ❌ 编译有损 | ✅ 读原文 | RAG / Agentic |
| 错误的持久性 | 幻觉一次性 | ❌ 编译错误持久污染 | 幻觉一次性 | RAG / Agentic |
| 时效性 | 分钟级重灌 | 小时级重编译 | 实时 | Agentic |
| 索引一致性风险 | 存在（SWA 断链为证） | 存在 + 编译一致性 | 不存在索引层 | Agentic |
| embedding 模型绑定 | 绑定（换模型重嵌入） | 绑定（查询侧检索时） | 无 | Agentic |
| 知识沉淀 | 无 | ✅ 语料级 | 无 | LLM Wiki |
| 评测方法学 | ✅ Recall/MRR/RAGAS 闭环 | 需另建主观评测 | 每次评测 = 跑一次 agent | RAG |
| 多租户/权限 | kb_id 过滤（可做对） | 条目级（可做） | 原文直通（难做对） | RAG / LLM Wiki |

没有全胜者：RAG 赢在"可服务、可评测、可扩展"，LLM Wiki 赢在"知识与 token 效率"，Agentic 赢在"零维护、零陈旧、深挖"。选择取决于语料类型、查询频率和查询深度。

---

## 7. 语料 × 场景选型

| 语料/场景 | 推荐 | 理由 |
|---|---|---|
| 代码库问答/重构 | **Agentic Search** | 词法锚点 + 导航结构齐备，时效性关键（索引必过期） |
| 医学/法律/合规文献（答案=原文） | **RAG** | 无损与溯源是准入项 |
| 企业内部知识库（长期积累、协作文档） | **LLM Wiki 或混合** | 沉淀价值 > 编译成本 |
| 新闻/舆情（高频更新、高 QPS） | **RAG** | 更新语义 + 服务能力 |
| 综述/竞品分析类查询为主 | **LLM Wiki 或 Agentic** | 综合理解是核心诉求 |
| 海量语料（百万级 chunk） | **RAG** | 另两者规模墙 |
| 小而稳定的核心制度文档（<100 篇） | **皆可，LLM Wiki 体验佳** | 编译成本可控 |
| 低频高价值深挖（每周几次） | **Agentic 或 ReAct** | 成本结构与价值对齐 |

本系统当前验证的场景（PubMedQA、CRUD-RAG）属第二行，**不存在换范式的动机**；但系统若扩展到代码问答或深度调研场景，第三、八行的能力值得补。

---

## 8. 本系统与三范式的关系：三个胚胎都已存在

这是本分析最重要的落地观察——RAGGG 不是"纯 RAG 系统"，它已经包含三种范式的胚胎：

| 范式 | 系统内对应物 | 现状 |
|---|---|---|
| RAG | hybrid 检索主线（Milvus+ES+RRF+精排） | ✅ 主力，已验证 |
| Agentic Search | **ReAct 引擎**（LLM 产出检索/查库/查历史动作 → 观察 → 再决策，循环 ≤5 轮）——与 Claude Code 的 grep 循环结构同构，只是检索工具从 grep 换成 hybrid 检索 | ⚠️ 已实现但默认关闭；分支不返回 sources；降级时把执行摘要当答案；LoopDetector 单例有并发污染 |
| LLM Wiki | 意图表 `t_intent_node`（条目级路由配置）+ 查询词映射 `t_query_term_mapping`（人工术语编译） | ❌ 三层半成品：表和域模型建了、前端 API 封装写了，Controller 和 UI 未落地 |

`ComplexityRouter` 的 SIMPLE/COMPLEX 分流，正是 agentic 范式的核心经济学："能一发检索解决就不开 agent，复杂问题才付多轮循环的成本"——架构直觉已与 Claude Code 对齐。

## 9. 混合演进路线（按性价比排序）

**路线 1：修复层级检索 SWA（最高性价比，代码已存在 80%）**
解决 F1/F3/F4（E1）。改动：Milvus schema 增 metadata 标量字段并加入 outputFields；ES 返回补 metadata；`HybridRetrievalService.RetrievalResult` 增加 metadata 字段。补一条端到端测试锁住"写入字段 = 读出字段"（把 F4 这类索引说谎问题永久锁死）。父块本身就是粗粒度的"预组织知识"，是编译思想的最小实现。

**路线 2：文档级编译摘要层（LLM Wiki 思想的受控引入）**
Kafka 流水线在 `document-chunked` 后加可选阶段：LLM 为每篇生成结构化摘要页，以 `chunkLevel=summary` 入索引；SUMMARY 意图加权命中摘要、PRECISE_SEARCH 走原文。关键设计：摘要**附加**而非替代原文 chunk（规避 L1/L4）；编译失败降级为跳过（规避时效）；只编译不合并（把 L5 排除在范围外）。

**路线 3：意图路由接线**
`IntentClassifier` 已输出五类意图、`t_intent_node` 已建表——接起来即为路线 2 的路由层，顺带把 LLM Wiki 的 W4 用 `t_query_term_mapping` 落地。

**路线 4：把 ReAct 补完成真正的 agentic search（吸收 Claude Code 范式）**
- 检索动作返回 sources，ReAct 分支接溯源（A4 的价值）；
- 降级路径改为回退真实 RAG 链路而非把执行摘要当答案；
- 修 LoopDetector/ActionCache 的单例并发污染，ActionCache 加 TTL 与容量上限；
- QUERY_DATABASE 动作加只读/白名单校验（当前是任意 SQL，安全问题）；
- 保持 ComplexityRouter 分流经济学不变。

**不建议：**
- 全量替换为编译式知识库（L1/L2/L4/L5，且两大基准口径直接失分）；
- 条目级合并/冲突消解（L5 成本收益比不成立）；
- 对自然语言语料引入纯 grep 链路（G1）。

## 10. 结论

1. **三种范式是三种成本下注，不是三代技术**：RAG 押索引（查询便宜、可服务可评测）、LLM Wiki 押编译（知识有组织、查询最便宜）、Agentic 押查询循环（零维护零陈旧、深挖强）。
2. **语料决定范式**：代码有词法锚点所以 Claude Code 的 grep 成立；论文/新闻同义改写密集所以 embedding 不可替代；长期团队知识适合编译沉淀。
3. **本系统不该换范式，该补完胚胎**：RAG 主线保持不动；SWA 修复（路线 1）最优先；LLM Wiki 思想以摘要层受控引入（路线 2/3）；agentic 思想通过补完 ReAct 落地（路线 4）。
4. **对展示/求职叙事**：能讲清"什么时候不该用编译式、什么时候 grep 不灵"（L1-L5 与 G1 的权衡）比追任一新范式更能体现工程判断力；"一次检索 + 受控编译 + agent 循环"的三层混合架构 + 三范式各自的成本模型，是完整的技术故事。

---

## 依据来源

本仓库内部（均为实测数据与代码走读）：

- [retrieval-eval-pubmedqa-2026-09-21.md](./testing/retrieval-eval-pubmedqa-2026-09-21.md) — PubMedQA 召回数据、rerank 静默失效发现
- [crud-rag-benchmark-2026-09-22.md](./testing/crud-rag-benchmark-2026-09-22.md) — CRUD-RAG 中文基准数据
- [ragas-eval-2026-09-22.md](./testing/ragas-eval-2026-09-22.md) — RAGAS 四指标、生成 p50 60s
- [kafka-pipeline-throughput-report-2026-09-21.md](./testing/kafka-pipeline-throughput-report-2026-09-21.md) — 摄入吞吐与瓶颈下钻
- [testing/记忆系统测试报告.md](./testing/记忆系统测试报告.md) — 记忆系统测试结论
- `stress-test/benchmark_chinese_query.py` — 跨语言查询用例（G1 的内部证据）
- 2026-09-24 代码架构走读（SWA 断链、rerank 回退、ReAct 实现细节等代码级证据）

外部（LLM Wiki 与 Agentic Search 概念与社区讨论）：

- [RAG已死？从 Claude Code 源码到行业实践看 Grep 的回归 - 知乎](https://zhuanlan.zhihu.com)
- [Agentic Search vs. RAG: Why Claude Code Doesn't Index - aiskill.market](https://aiskill.market)
- [为什么 Claude Code 不用 RAG - CSDN](https://blog.csdn.net)
- [Why I'm Against Claude Code's Grep-Only Retrieval - Milvus](https://milvus.io)
- [LLM Wiki 和 RAG 的区别 - 知乎](https://zhuanlan.zhihu.com)
- [从知识图谱 RAG 到 LLM Wiki：原理、区别与选型 - 腾讯云开发者社区](https://cloud.tencent.com/developer)
- [Karpathy LLM Wiki 实践：用"知识编译"替代 RAG - 腾讯云开发者社区](https://cloud.tencent.com/developer)
- [从 RAG 到 LLM Wiki，再到 Agentic 知识库 - GitCode/CSDN](https://gitcode.csdn.net)
- [LLM Wiki vs RAG: A Decision Framework - MindStudio](https://www.mindstudio.ai)

> 注：外部文章用于概念界定与社区观点归属；本文所有量化结论（成本倍数、延迟、命中率）均以本仓库实测数字换算，未引用外部未经验证的数字。
