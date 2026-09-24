# PubMedQA 召回效果评测报告

- **日期**：2026-09-21
- **被测系统**：RAGGG 后端（localhost:8080），KB 19（PubMedQA召回评测-…）
- **评测目标**：用带 ground truth 的公开数据集量化检索召回质量，对比三种检索模式

## 1. 评测方法（可复用的数据集测试路线）

1. **数据集**：HuggingFace `rungalileo/ragbench` 的 `pubmedqa` test split（直连被墙，经 `HF_ENDPOINT=https://hf-mirror.com` 镜像拉取），取 200 条，196 条可用（4 条缺 golden 标注被剔除）
2. **Ground truth**：PubMedQA 自带 `all_relevant_sentence_keys`（人工标注的相关句子）→ 每问题 1–17 条 golden 句
3. **灌入**：每条问题的 context（abstract，746–3420 字符）作为一篇 txt，走**真实流水线**（upload→Kafka→parse→chunk→index）灌入新 KB，fixed 分块，196 篇 / 860 chunks，72 秒完成、0 失败
4. **检索调用**：每问题 × 三模式（topK=10）：
   - `vector`：`POST /api/v1/retrieve`（rerank=false，纯 Milvus 向量）
   - `hybrid`：`POST /api/v1/retrieve/hybrid`（rerank=false，Milvus+ES 双路 RRF）
   - `hybrid+rr`：`POST /api/v1/retrieve/hybrid`（rerank=true）
5. **命中判定**：检索 chunk 归一化后包含任一 golden 句（全文 / 首尾 60 字符三重判定）
6. **指标**：Hit@3/5/10、MRR@10、延迟 mean/P95

## 2. 评测结果（196 queries）

第一轮（rerank provider = siliconflow，配置原状）：

| mode | Hit@3 | Hit@5 | Hit@10 | MRR@10 | lat_mean |
|---|---|---|---|---|---|
| vector | 99.0% | 99.5% | 100.0% | 0.938 | 0.192s |
| hybrid | 99.0% | 100.0% | 100.0% | 0.935 | 0.194s |
| hybrid+rr | 99.0% | 100.0% | 100.0% | 0.935 | 0.227s |

⚠️ rerank 在 **0/196** 个查询上改变排序，且只增加 36ms——不是真实的 API 往返。

**根因**：`config.yaml` 里的 SiliconFlow key 已失效（401 `Token is invalid`，直接 curl 复现）。`SiliconFlowReranker` 按"静默回退"设计返回未重排候选，**不会**落到本地 CrossEncoderReranker（与代码分析结论一致，本次实测证实）。git 历史（"原 key 已吊销"）印证。

第二轮（provider 临时切为 `crossencoder` 本地 Bi-Encoder，测后已还原配置）：

| mode | Hit@3 | Hit@5 | Hit@10 | MRR@10 | lat_mean |
|---|---|---|---|---|---|
| vector | 99.0% | 99.5% | 100.0% | 0.938 | 0.192s |
| hybrid | 99.0% | 100.0% | 100.0% | 0.935 | 0.194s |
| hybrid+rr(本地) | 99.0% | 99.5% | 100.0% | 0.938 | **6.403s** |

本地精排确实执行了（196/196 查询排序被改变），但质量零提升（Hit@5 反而略降），延迟 **33 倍**（每查询 11 次串行 Ollama embedding）。

## 3. 结论

1. **召回质量本身优秀**：纯向量 Hit@10 已达 100%，hybrid 在 Hit@5 上略优（+0.5pp）。bge-m3 对"问题≈文献标题"型查询非常有效。
2. **本数据集对模式无区分度（天花板效应）**：196 篇小语料、问题与文档强相关，三种模式都接近满分。要区分 vector/hybrid/rerank 的真实差异，需要更难的设置：
   - 大干扰语料（把 KB18 的 500+ 篇混入同一检索空间）
   - 语义改写查询（问题换一种问法，削弱词面重叠）
   - 跨语言查询（中文问题检索英文语料，项目自带 `benchmark_chinese_query.py` 思路）
3. **rerank 现状 = 实际失效**：SiliconFlow key 已死被静默旁路；本地回退零收益、33 倍延迟。**建议**：拿到新 key 前将 `retrieval.rerank.enabled` 置 false（省 36ms/次的无效调用），或补充有效 key 恢复真 CrossEncoder 精排。
4. 延迟基线：向量/混合检索 ~0.19s（其中查询 embedding ~0.15s 为大头）。

## 4. 工件位置

| 文件 | 说明 |
|---|---|
| `stress-test/data/pubmedqa_golden.jsonl` | 196 条评测集（question/doc_text/golden_sentences） |
| `stress-test/eval_pubmedqa_recall.py` | 评测脚本（ingest/eval 两阶段，可复用） |
| `stress-test/data/pubmedqa_eval_result_2026-09-21.json` | 原始结果 |
| 数据源 | `HF_ENDPOINT=https://hf-mirror.com` + `rungalileo/ragbench:pubmedqa[test]` |
