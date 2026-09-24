# 分块策略详解

> **文档性质**：`domain/chunking/` 五种分块策略的现状说明，2026-09-24 与代码对账。
> 策略选择：上传参数 `chunkStrategy`（fixed/structural/semantic/intelligent/swa）；工厂 `ChunkStrategyFactory.getStrategy(name, params)`，**params 现在透传上传时的自定义参数**（chunkSize/chunkOverlap/minParagraphLength/maxParagraphLength/maxTokensPerChunk/minTokensPerChunk，2026-09-24 修复——此前传空 Map 全部无效）。
> 检索侧消费见 [02-core-components 第三节](./02-core-components.md)。

## 0. 策略速览

| 策略 | 适用 | 粒度控制 | 核心机制 |
|---|---|---|---|
| `fixed` | 通用/TXT/CSV | chunkSize=512, overlap=50 | 定长滑动窗口 + 单词边界保护 |
| `structural` | PDF/Word/HTML/Markdown | min=300, max=2000 字符 | 结构识别（标题/段落/列表/代码块）+ 合并 |
| `semantic` | 长文档 | maxTokensPerChunk=512 | 规则切句 + 两轮主题聚合（**非 embedding 语义**） |
| `intelligent` | 混合文档 | 按 MIME 映射预置参数 | 类型检测决策树 → 委派上述策略 |
| `swa` | 长文档精准召回 | parentChunkSize=512 token | 父子双层分块（层级检索的存储侧） |

## 1. FixedChunkStrategy（fixed）

- 滑动窗口：`step = chunkSize - overlap`；`findWordBoundary` 在切点前后 50/20 字符内找空白，避免切词
- token 估算：中文字符 ×1 + 英文字符 ÷4
- metadata：offset/endOffset
- ⚠️ 遗留：`findSmartOverlapStart`（句子/段落边界智能回退）为死代码，主流程未调用；全局 `chunking.chunk-size/overlap` 配置未注入工厂（实际上传参数才生效）

## 2. StructuralChunkStrategy（structural）

- **结构识别**：SegmentType = HEADING / LIST_ITEM / PARAGRAPH / CODE_BLOCK（``` 围栏代码块整体保护不切）
- **mergeAndGroup**：短段（< min/2）与邻段合并；达 min 或超 max flush；超长段按段落二次切分
- metadata 带 heading 路径；质量告警：<50 token 记 "too_small"、>1500 记 "too_large"

## 3. SemanticChunkStrategy（semantic）

> **命名澄清**：此策略**不计算 embedding 相似度**，是"规则近似语义"——中文标点 + 14 个连接词（但是/然而/因此…）切句，再按 token 预算两轮聚合。真正的语义切分是已知差距（known-gaps 🟢）。

四步：① 连接词+标点切句（>10000 字符递归对半）→ ② 短句合并 → ③ 两轮聚合：先按 maxTokensPerChunk 切无重叠 rawChunks，再滑动合并（上限 1.5×max，尾部 overlapTokens=200 句子作为下一块开头）→ ④ chunk 化。token 公式：中文×1.5 + 英文÷4 + 标点×0.5。

## 4. IntelligentChunkingStrategy（intelligent）

决策树 = **MIME > 文件名 > 内容特征**（`DocumentTypeDetector`：MIME 表 / 扩展名 / 内容打分——markdown≥4 指示符、代码≥5 指示符、CSV 列数一致性）。

11 种 DocumentType 映射预置参数：

| 类型 | 委派策略 | 参数示例 |
|---|---|---|
| MARKDOWN / HTML | structural | 300/3000/150 |
| PDF_TEXT / DOC_TEXT | semantic | 200/512/50 |
| XLSX / CSV / JSON | fixed | 默认 |
| CODE | fixed(300) | |
| 纯文本 | fixed | |

参数换算：overlap=chunkSize/5、maxTokens=chunkSize/4、overlapTokens=chunkSize/20。

## 5. HierarchicalChunkStrategy（swa）——层级检索存储侧

为 Sentence Window + Auto-Merging 生成**父子双层结构**：

```
原文 → 按 。！？； 切句 → 聚合为 Parent（parentChunkSize=512 token，超长句截断）
                                    ↓
       每个句子单独生成 Leaf chunk，metadata 携带：
       parentChunkId / position / siblingCount
       windowContent（前后各 windowSize=3 句）/ chunkLevel=leaf
Parent 本身也入库（chunkLevel=parent）
```

**数据通路（2026-09-24 断链修复后）**：metadata 经 IndexService 写入 ES（动态字段）与 Milvus（schema 的 metadata VarChar 列，JSON 序列化），检索时 `outputFields` 带回 → `HybridRetrievalService.RetrievalResult.metadata` → `HierarchicalRetrievalService` 消费（Auto-Merging/Sentence Window，回归测试锁定）。

> 升级注意：存量 Milvus 集合无 metadata 字段时启动自动重建，swa 文档需重灌。
> 另：`t_chunk_hierarchy` 表（23-hierarchical-chunks.sql）是同期的规划存储，**零引用**（现行路线走 Milvus/ES metadata，与该表无关）。

## 6. 策略与 token 估算口径

分块层的 token 估算（fixed：中文1/英文0.25；semantic：中文1.5/英文0.25/标点0.5）与记忆系统口径（中文1/英文0.25）不完全一致——历史实现差异，影响仅限 metadata 里的 tokenCount 字段，不影响检索。
