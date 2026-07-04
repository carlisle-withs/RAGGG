---
name: raggg-test
description: Use when the user asks to run tests, verify the system, or check API functionality for the RAGGG project. Covers all 61 REST API endpoints across 14 controllers including health check, auth, chat, conversations, knowledge base, retrieval, embedding, RAG, and dashboard.
---

# RAGGG 自动化测试

## 快速开始

当用户说"测试"、"跑测试"、"检查接口"、"验证功能"时，执行以下流程：

### 1. 确认服务运行

```bash
curl -s http://localhost:8080/actuator/health | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])"
```

如果返回 `UP`，继续测试。如果返回 `DOWN` 或无响应：

```bash
ps aux | grep 'rag-demo' | grep -v grep || echo "NOT RUNNING"
```

如果未运行，启动服务：

```bash
kill $(ps aux | grep 'rag-demo' | grep -v grep | awk '{print $2}') 2>/dev/null; sleep 2
java -Xmx2g -Xms512m -jar /home/yurisa/workspace/RAGGG/target/rag-demo-1.0.0-SNAPSHOT.jar > /tmp/backend.log 2>&1 &
```

等 25 秒后再次检查 health。

### 2. 运行测试

**完整测试**（含 LLM 对话，约 60 秒）：
```bash
bash /home/yurisa/workspace/RAGGG/test-agent.sh
```

**快速测试**（跳过 LLM 调用，约 10 秒）：
```bash
bash /home/yurisa/workspace/RAGGG/test-agent.sh --quick
```

**详细输出**：
```bash
bash /home/yurisa/workspace/RAGGG/test-agent.sh --verbose
```

### 3. 解读结果

测试覆盖 15 个模块共 61 个接口：

| 编号 | 模块 | 接口数 | 说明 |
|------|------|--------|------|
| 1 | Health | 1 | 服务健康检查 |
| 2 | Auth | 5 | 登录/注册/刷新/登出/错误密码 |
| 3 | User | 1 | /user/me |
| 4 | Chat | 1 | POST /chat (LLM 对话) |
| 5 | Conversation | 5 | 会话列表/重命名/消息/反馈/删除 |
| 6 | KnowledgeBase CRUD | 8 | 知识库 CRUD + chunk策略 + 重索引 + 搜索 |
| 7 | KnowledgeBase Docs | 10 | 文档上传/状态/chunks/批量操作 |
| 8 | Document | 5 | 文档列表/上传/批量上传/状态/删除 |
| 9 | Retrieval | 2 | 检索/混合检索 |
| 10 | Embedding | 2 | 单条/批量 embedding |
| 11 | Evaluation | 2 | 评估/批量评估 |
| 12 | Ingestion | 2 | 流水线列表/详情 |
| 13 | RAG | 6 | 示例问题/设置/追踪/停止任务 |
| 14 | RAG V3 SSE | 1 | SSE 流式对话 |
| 15 | Dashboard | 3 | 概览/性能/趋势 |

### 4. 如果有失败

查看具体失败项：
```bash
bash /home/yurisa/workspace/RAGGG/test-agent.sh 2>&1 | grep '✗'
```

常见失败原因：
- **服务未启动** → 检查进程、端口、日志 `/tmp/backend.log`
- **LLM API 超时** → 使用 `--quick` 跳过 LLM 测试
- **数据不存在** → 部分接口需要预置知识库/文档，失败属于正常跳过

### 5. 开发守护进程

代码变更后自动构建+测试：
```bash
# 后台运行
bash /home/yurisa/workspace/RAGGG/watchdog.sh --daemon

# 查看状态
bash /home/yurisa/workspace/RAGGG/watchdog.sh status

# 停止
bash /home/yurisa/workspace/RAGGG/watchdog.sh stop
```

### 6. 代码变更后的完整部署流程

当修改了 Java 或前端代码后：

```bash
# 1. 前端构建
cd /home/yurisa/workspace/RAGGG/frontend && npm run build && cp -r dist/* ../src/main/resources/static/

# 2. 后端构建
cd /home/yurisa/workspace/RAGGG && mvn package -DskipTests -q

# 3. 重启
kill $(ps aux | grep 'rag-demo' | grep -v grep | awk '{print $2}') 2>/dev/null; sleep 2
java -Xmx2g -Xms512m -jar target/rag-demo-1.0.0-SNAPSHOT.jar > /tmp/backend.log 2>&1 &

# 4. 等25秒后测试
sleep 25 && bash /home/yurisa/workspace/RAGGG/test-agent.sh --quick
```
