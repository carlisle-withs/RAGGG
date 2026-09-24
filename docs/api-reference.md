# API 接口参考

> **文档性质**：全量 REST 接口参考，2026-09-24 与代码对账（19 个 Controller、60+ 端点）。
> 运行时可访问 Knife4j 交互文档：`http://<host>:<port>/doc.html`。
> 架构背景见 [01-architecture-overview](architecture/01-architecture-overview.md)。

## 通用约定

- **Base URL**：`http://<host>:8080/api/v1`（无 config.yaml 时端口 8081）
- **认证**：除标注 🌐 公开的端点外，**全部要求 `Authorization: Bearer <JWT>`**（401 返回 `{"error":"UNAUTHORIZED","message":"Authentication required"}`）
- **权限**：🔵 标注端点要求 ADMIN 角色（403 返回 `{"error":"FORBIDDEN",...}`）；对话/检索类另有 KB 归属校验
- **响应包裹**：部分业务接口返回 `{code:"0", data:...}` 包裹（前端 api.ts 统一解包）；本表按 Controller 原始返回描述
- **上传限制**：单文件 2GB（multipart max-file-size）

---

## 1. 认证 `/auth` 🌐 公开

| 端点 | 说明 | 请求 | 响应 |
|---|---|---|---|
| `POST /auth/register` | 注册 | `{username, password}` | `{token, refreshToken, user{id,username,role}, expiresIn}` |
| `POST /auth/login` | 登录 | `{username, password}` | 同上；401 `INVALID_CREDENTIALS` |
| `POST /auth/refresh` | 刷新 | `{refreshToken}` | 新 token 对；**校验 type=refresh claim**（access token 不可自续） |
| `POST /auth/logout` | 登出 | - | 200（客户端丢弃 token；服务端无撤销） |

## 2. 对话

### `POST /chat` —— 同步 RAG 问答

请求：`{message*, conversationId?, userId?, kbIds?: [id], stream?}`

链路：记忆 → 复杂度路由 → （SIMPLE）意图分类 → 统一检索管线 → 生成 /（COMPLEX）ReAct 引擎（降级自动回退 RAG）。

响应：`{conversationId, message, sources: [{chunkId, content, score}], tokens, intent, routedKbIds}`

- `sources` 含 ReAct 分支聚合的检索命中（去重前 5 条）
- kbIds 只取第一个；KB 无权限返回 403

### `POST /chat/stream` —— 流式对话（前端主链路）

请求同上；KB 归属校验同 /chat。**响应为 NDJSON**（text/plain，每行一个 JSON）：

```
{"type":"meta","conversationId":"..."}
{"type":"token","delta":"生成中的文本片段"}     ← 0..N 次
{"type":"finish","conversationId":"...","fullLength":1234,
 "sources":[{"chunkId":"...","content":"(截断至200字)","score":0.87}]}
```

- 超时 300s（Emitter）；生成完成后 assistant 消息（含 sources JSON）持久化到 `t_message`
- 错误：`{"type":"error","error":"..."}` 后结束
- 前端 `chatStore.sendMessage` 用 fetch reader 按行解析；引用面板渲染 sources

### `GET /rag/v3/chat` —— SSE 流式（@Deprecated）

旧链路，具名事件（meta/message/finish/done/error），前端已不使用。

## 3. 会话 `/conversations`

| 端点 | 说明 | 响应 |
|---|---|---|
| `GET /conversations` | 会话列表 | `[{conversationId, title, lastTime}]` |
| `PUT /conversations/{id}` | 重命名 | `{title}` → 200 |
| `DELETE /conversations/{id}` | 删除 | 200 |
| `GET /conversations/{id}/messages` | 历史消息 | `[{id, conversationId, role, content, thinkingContent, thinkingDuration, vote, createTime, sources}]`——**sources 含引用回显**（Redis 快路径按 id 补齐 / MySQL 直读） |
| `POST /conversations/messages/{messageId}/feedback` | 点赞/点踩 | `{vote: 1\|-1, content?}` → 200 |

## 4. 知识库 `/knowledge-base`

基础 CRUD：

| 端点 | 说明 |
|---|---|
| `GET /knowledge-base` | 列表（含 documentCount） |
| `POST /knowledge-base` | 创建 `{name, description?, embeddingModel?, chunkStrategy?}` |
| `GET / PUT / DELETE /knowledge-base/{id}` | 详情 / 更新 / 删除 |
| `GET /knowledge-base/chunk-strategies` | 可用分块策略枚举 |

文档管理（挂 KB 下）：

| 端点 | 说明 |
|---|---|
| `GET /knowledge-base/{kbId}/docs` | 文档列表（状态/分块数） |
| `POST /knowledge-base/{kbId}/docs/upload` | 上传文档到 KB（multipart；chunkStrategy/chunkSize 等可选参数） |
| `POST /knowledge-base/{kbId}/docs/batch-upload` | 批量上传 |
| `GET /knowledge-base/docs/{docId}` | 文档详情 |
| `PUT /knowledge-base/docs/{docId}` | 更新（启停等） |
| `POST /knowledge-base/docs/{docId}/chunk` | 触发重分块（走 Kafka 流水线） |
| `DELETE /knowledge-base/docs/{docId}` | 删除（清理索引） |
| `GET /knowledge-base/docs/{docId}/chunk-logs` | 分块日志 |
| `GET /knowledge-base/docs/{docId}/chunks` | 分块列表 |
| `POST /knowledge-base/docs/{docId}/chunks` | 新增分块 |
| `PUT /knowledge-base/docs/{docId}/chunks/{chunkId}` | 编辑分块（重嵌入） |
| `DELETE /knowledge-base/docs/{docId}/chunks/{chunkId}` | 删除分块 |
| `POST /knowledge-base/{kbId}/reindex` | 全库重建索引 |
| `GET /knowledge-base/docs/search` | 文档联合搜索（管理端顶栏） |

## 5. 文档 `/documents`（独立入口）

| 端点 | 说明 |
|---|---|
| `GET /documents?kbId=` | 文档列表（状态轮询用，评测脚本依赖） |
| `POST /documents/upload` | 上传：`file* + kbId + chunkStrategy(fixed) + chunkSize? + chunkOverlap? + minParagraphLength? + maxParagraphLength? + maxTokensPerChunk? + similarityThreshold?`（参数透传分块器） |
| `POST /documents/upload/batch` | 批量上传 |
| `GET /documents/{id}/status` | 处理状态（PENDING→…→COMPLETED/FAILED） |
| `DELETE /documents/{id}` | 删除 |

## 6. 检索调试 `/retrieve`

| 端点 | 说明 |
|---|---|
| `POST /retrieve` | 纯向量检索：`{query*, kbIds, topK=10, rerank=false}` |
| `POST /retrieve/hybrid` | 混合检索：`{query*, kbIds, topK=10, rerank}`——**评测脚本主入口**（PubMedQA/CRUD-RAG 基准经此调用） |

响应：`{results: [{chunkId, content, score, relevance}], mode, latencyMs, total}`；KB 无权限 403。

## 7. 词映射 `/mappings`（2026-09-24 落地）

| 端点 | 说明 |
|---|---|
| `GET /mappings?current=1&size=10&keyword=` | 分页列表（keyword 模糊匹配 source/target）→ `{records:[{id,sourceTerm,targetTerm,matchType,priority,enabled,remark,createTime,updateTime}], total, size, current, pages}` |
| `POST /mappings` | 创建 `{sourceTerm*, targetTerm*, matchType?, priority?, enabled?, remark?}` → 新 id |
| `PUT /mappings/{id}` | 更新 |
| `DELETE /mappings/{id}` | 软删除 |
| `POST /mappings/preview` | 调试：`{query}` → `{original, mapped}`（预览术语归一化效果） |

规则语义：`matchType 1`=包含即替换、`2`=整词边界替换；`priority` 小者优先；启用规则经 60s 缓存接入检索管线（管理端变更即失效缓存）。

## 8. 意图树 `/intent-tree`（2026-09-24 落地基础版）

| 端点 | 说明 |
|---|---|
| `GET /intent-tree/trees` | 树形结构（parentCode 组装 children）：`[{id, intentCode, name, level, parentCode, description, examples[], collectionName, mcpToolId, topK, kind, sortOrder, enabled, promptSnippet, promptTemplate, paramPromptTemplate, children[]}]` |
| `POST /intent-tree` | 创建：`{kbId?, intentCode*, name*, level, parentCode?, examples?: string[], ...}` |
| `PUT /intent-tree/{id}` | 更新（部分字段） |
| `DELETE /intent-tree/{id}` | 软删除 |
| `POST /intent-tree/batch/{enable\|disable\|delete}` | 批量操作 `{ids: [number]}` → 影响行数 |

> 边界：检索路由消费节点配置（topK/collection/提示词）**尚未接线**——见 known-gaps。

## 9. 链路追踪 `/rag/traces`（2026-09-24 落地）

| 端点 | 说明 |
|---|---|
| `GET /rag/traces/runs?current=1&size=10&traceId=&conversationId=&taskId=&status=` | 分页过滤（traceId/conversationId 模糊，taskId/status 精确）→ `{records:[TraceRun], total, ...}` |
| `GET /rag/traces/runs/{traceId}` | 详情：`{run: TraceRun, nodes: TraceNode[]}` |
| `GET /rag/traces/runs/{traceId}/nodes` | 仅节点列表 |

`TraceRun: {traceId, traceName, entryMethod, conversationId, taskId, username, status(COMPLETED/FAILED), errorMessage, durationMs, startTime, endTime}`

`TraceNode: {nodeId, parentNodeId, depth, nodeType(step), nodeName(condense/term_mapping/expand/hybrid_retrieval/context_assembly), className, methodName, status, durationMs, startTime, endTime, extraData(JSON)}`

每次 RAG 检索（RagContextService.build）产生一条 run + 4-5 个节点，前端 Traces 页瀑布展示。

## 10. 评测 `/evaluation`

| 端点 | 说明 |
|---|---|
| `POST /evaluation/evaluate` | 单条 RAGAS：`{question, answer, contexts[], groundTruth?}` → 四指标 |
| `POST /evaluation/evaluate/batch` | 批量（stress-test/eval_ragas_pipeline.py 调用） |

指标：faithfulness / answerRelevancy / contextPrecision / contextRecall + 综合（0.3/0.3/0.2/0.2 加权）；判分与生成同源模型的自评偏置见 [ragas-eval 报告](testing/ragas-eval-2026-09-22.md)。

## 11. 灌入与调试

| 端点 | 说明 |
|---|---|
| `GET /ingestion/pipelines`、`GET /ingestion/pipelines/{id}` | 灌入管线定义（只读） |
| `POST /embedding/embed`、`/embedding/embed/batch` | Embedding 调试：`{text}` / `{texts[]}` → 向量 |
| `GET /rag/sample-questions` | 示例问题（欢迎页预设提示；stub 返回空，另有 `/sample-questions` CRUD 见下） |
| `GET /sample-questions`、`POST`、`PUT /{id}`、`DELETE /{id}` | 示例问题管理 |
| `GET /rag/settings` | 运行时配置视图（静态快照：上传限制/RAG 默认/记忆参数/AI provider 候选组） |
| `POST /rag/v3/stop` | ⚠️ stub（返回 200 无取消逻辑） |

## 12. 多模态（`llm.multimodal.enabled` 开启后可用）

| 端点 | 说明 |
|---|---|
| `POST /chat/vision` | 图像理解对话（imageBase64 + question） |
| `POST /chat/multimodal` | 图文混合多模态对话 |
| `POST /images/analyze` | 单图解析（结构化提取） |
| `GET /chat/multimodal/status` | 多模态能力状态 |

详见 [06-multimodal](architecture/06-multimodal.md)。

## 13. 管理端 `/admin` 🔵 ADMIN

| 端点 | 说明 |
|---|---|
| `GET /admin/dashboard/overview` | 总览统计（用户/文档/会话量） |
| `GET /admin/dashboard/performance` | 性能指标（延迟/吞吐） |
| `GET /admin/dashboard/trends` | 趋势序列 |

## 14. 用户 `/user`

| 端点 | 说明 |
|---|---|
| `GET /user/me` | 当前用户信息（管理端顶栏/改密对话框用） |

## 15. 监控 🌐

| 端点 | 说明 |
|---|---|
| `GET /actuator/health`、`/actuator/prometheus`、`/actuator/metrics` | 健康检查与指标（Prometheus 抓取，**当前公开**——生产建议内网隔离） |
| `GET /doc.html` | Knife4j 交互式 API 文档 |

---

## 附：评测脚本常用调用组合

```bash
# 登录取 token
TOKEN=$(curl -s -X POST :8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r .token)

# 混合检索评测（PubMedQA/CRUD-RAG 基准入口）
curl -X POST :8080/api/v1/retrieve/hybrid -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"...","kbIds":["19"],"topK":10,"rerank":true}'

# 上传 + 状态轮询（评测灌库）
curl -X POST :8080/api/v1/documents/upload -H "Authorization: Bearer $TOKEN" \
  -F file=@doc.pdf -F kbId=19 -F chunkStrategy=fixed
curl ":8080/api/v1/documents?kbId=19" -H "Authorization: Bearer $TOKEN"
```

脚本全集与用法见 [stress-test/README](../stress-test/README.md) 与 [运维手册](guides/03-operations.md)。
