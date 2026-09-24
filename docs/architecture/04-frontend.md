# 前端架构

> **文档性质**：前端现状文档，2026-09-24 与 `frontend/src/` 对账（60+ 源文件）。
> 总览见 [01-architecture-overview](./01-architecture-overview.md)；API 契约见 [api-reference](../api-reference.md)。

## 一、技术栈

React 18.3 + TypeScript + Vite 5 + react-router-dom 6（createBrowserRouter）+ zustand 4.5（状态）+ axios（HTTP）+ Tailwind CSS + Radix UI/shadcn 风格组件 + react-markdown/remark-gfm + react-syntax-highlighter + sonner（toast）+ recharts（图表）+ react-dropzone / react-virtuoso。

构建产物 `frontend/dist`，由后端作为静态资源分发（`/index.html`，WebMvcConfig 带 SPA 回退）；watchdog.sh 开发时可自动构建并拷贝到 `src/main/resources/static/`。

## 二、路由结构（router.tsx）

```
/                → 重定向 /chat
/login           → LoginPage
/chat            → ChatPage（对话主界面）
/chat/:sessionId → ChatPage（加载指定会话）
/admin           → AdminLayout（嵌套，侧边栏分组"导航/设置"）
  ├ dashboard    → DashboardPage        KPI + 趋势图（recharts）+ 洞察卡
  ├ knowledge         → KnowledgeListPage      知识库列表
  ├ knowledge/:kbId   → KnowledgeDocumentsPage 文档上传/重切分/启停（1798 行，最大页面）
  ├ knowledge/:kbId/docs/:docId → KnowledgeChunksPage 分片 CRUD/批量启停
  ├ traces        → RagTracePage          运行列表 + P50/P95 统计卡
  ├ traces/:id    → RagTraceDetailPage    节点瀑布时间线（traceUtils 组装）
  ├ settings      → SystemSettingsPage    只读展示 /rag/settings
  └ sample-questions → SampleQuestionPage 示例问题 CRUD
*                → NotFoundPage
```

AdminLayout 附加：顶栏知识库/文档联合搜索下拉（200ms 防抖，`GET /knowledge-base/docs/search`）、GitHub Star 数实时拉取、修改密码对话框、面包屑、可折叠侧边栏。

## 三、状态管理（zustand，三 store）

| Store | 职责 | 关键点 |
|---|---|---|
| `chatStore`（430 行，核心） | 会话列表/消息/流式状态/activeKbId/深度思考开关 | `sendMessage` 内联实现 NDJSON 流式（见下）；登录登出由 authStore 直接 `useChatStore.setState()` 联动清空 |
| `authStore` | 登录态/token（localStorage 持久化） | 401/"未登录"消息时清 auth 跳 /login |
| `themeStore` | 明暗主题 | |

已知遗留：`chatStore` 默认 `activeKbId: "2"` 硬编码，与 ChatInput"加载 KB 后强制选第一个"互相覆盖（known-gaps 🟢）。

## 四、网络层

**axios（`services/api.ts`）**：请求拦截器注入 `Authorization: Bearer`（storage.getToken）；响应拦截器统一解包 `{code:"0", data}` 格式、401 跳登录、错误 toast。所有常规请求走这一层。

**流式 fetch（chatStore.sendMessage 内联）**：`POST /chat/stream` 带 `Accept: text/event-stream`，`res.body.getReader()` + TextDecoder **按行解析 NDJSON**；事件处理：`meta`（记录 conversationId）、`token`（appendStreamContent 追加）、`finish`（**解析 sources 挂到消息**）；`done` 后置 status:"done"。

**services/ 目录**（API 封装，与后端一一对应）：auth / chat（stopTask/feedback）/ session（会话与历史，**VO 含 sources**）/ knowledge / dashboard / ragTrace / settings / sampleQuestion / ingestion / user / **queryTermMapping（词映射 CRUD）** / **intentTree（意图树，批量操作）**。

**死代码提示**：`hooks/useStreamResponse.ts` 是旧 v3 SSE 链路的完整解析器（事件/重试/AbortController），当前零引用。

## 五、对话界面关键实现

- **消息渲染**：react-markdown + GFM；代码块带语言标签/复制/主题化高亮；用户消息纯文本
- **引用来源面板**（MessageItem）：assistant 消息的 `sources` 非空时显示可折叠面板——编号 + chunkId + 相关度分数（4 位小数）+ 内容预览（line-clamp-3）；来源两路：流式 finish 事件 / 历史接口回显
- **流式等待**：CSS 三点动画（`ai-wait-dots`），无打字机特效（token 直出）
- **深度思考**：开关 + 可折叠思考气泡 + `appendThinkingContent` 全在，但**流式链路无 think 事件**，仅 DB 历史的 thinkingContent 生效（known-gaps 记录的空壳）
- **反馈**：FeedbackButtons 乐观更新 + 失败回滚；仅服务器 id（非 `assistant-` 前缀）的消息显示——流式刚生成完看不到，刷新后可见
- **会话列表**：SessionList/SessionItem，切换走 loadSession（历史消息映射含 sources/feedback）

## 六、组件结构

```
components/
├── chat/      ChatInput（深度思考开关/KB 选择）、MessageList、MessageItem（引用面板）、
│              MarkdownRenderer、FeedbackButtons、ThinkingIndicator、WelcomeScreen
├── session/   SessionList、SessionItem
├── layout/    MainLayout、Header、Sidebar
├── admin/     管理端各页组件
├── common/    Toast、Loading、Avatar、ErrorBoundary
└── ui/        shadcn 风格基础件（dialog/dropdown/table/... 15 个）
```

## 七、构建与开发

```bash
cd frontend
npm install
npm run dev      # Vite 开发服务器
npm run build    # 产物 dist/（后端分发；构建告警 chunk>500KB，可做 manualChunks 拆分）
npm run lint     # eslint --max-warnings 0
```

环境变量：`.env` 的 `VITE_API_BASE_URL`（空 = 同源，随后端 8080 分发）。

已知遗留：`authStore.ts` / `MarkdownRenderer.tsx` 顶部 `// @ts-nocheck`（模板改造痕迹）；GitHub Star 拉取离线显示 "--"。

## 八、与后端契约的对齐点（2026-09-24）

| 契约 | 状态 |
|---|---|
| NDJSON 三事件（meta/token/finish+sources） | ✅ 对齐 |
| 认证：axios 注入 Bearer + 流式 fetch 手动携带 | ✅ 安全收紧后无需改动 |
| 历史消息 sources 回显 | ✅ sessionService VO + loadSession 映射 |
| 词映射 /mappings、意图树 /intent-tree | ✅ service 封装与后端契约一致（管理 UI 页面待做） |
| traces 数据 | ✅ 后端已返回真实数据，瀑布页即用 |
| v3 SSE / useStreamResponse / stopTask | ⚠️ 旧链路死代码，后端 stop 为 stub |
