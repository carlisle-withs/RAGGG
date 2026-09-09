# RAGGG 压测文档

## 压测脚本说明

### 1. Python 全链路压测 (`document-upload-stress.py`) ⚡ 推荐

**优点**: 跨平台、支持状态轮询、实时输出 P50/P95/P99 报告

```bash
# 安装依赖
pip install -r requirements.txt

# 基本运行 (20并发, 200请求)
python document-upload-stress.py

# 高级参数
python document-upload-stress.py --concurrency=50 --requests=500 --url=http://localhost:8080 --kb=1

# 持续压测模式 (30秒)
python document-upload-stress.py --concurrency=30 --duration=30

# 低并发摸底 (单线程测延迟基线)
python document-upload-stress.py --concurrency=1 --requests=10
```

**输出说明**:
- `P50/P95/P99 延迟`: 上传请求的端到端耗时
- `解析延迟`: 从上传到 `COMPLETED` 状态的时间
- `QPS`: 吞吐量 (成功请求/秒)

---

### 2. JMeter 压测 (`document-upload-test.jmx`)

**优点**: 成熟的压测报告生成、适合稳态压测

```powershell
# 确保 JMETER_HOME 设置
$env:JMETER_HOME = "C:\Program Files\apache-jmeter"

# 准备测试数据 (生成 1MB/5MB/10MB PDF)
.\prepare-test-data.ps1

# 运行压测
.\run-stress-test.ps1
```

**报告输出**:
- `results/results.jtl` — 原始数据
- `results/html-report/index.html` — HTML 可视化报告

---

## 压测场景设计

### 场景 A: 基准摸底 (单并发, 找延迟基线)

```bash
python document-upload-stress.py --concurrency=1 --requests=10
```

目的: 测出单个请求的基准延迟，排除网络和连接池竞争干扰

预期数据:
| 指标 | 预期值 |
|------|--------|
| P50 上传延迟 | 200~500ms (取决于文件大小) |
| P99 上传延迟 | <2s |
| 解析总耗时 | 取决于文件大小和 Tika 解析时间 |

### 场景 B: 并发压测 (逐步加压)

```bash
# 10 并发
python document-upload-stress.py --concurrency=10 --requests=100
# 30 并发
python document-upload-stress.py --concurrency=30 --requests=100
# 50 并发
python document-upload-stress.py --concurrency=50 --requests=100
```

目的: 找到系统的 QPS 上限和延迟开始恶化的拐点

### 场景 C: 稳态压测 (长时间运行)

```bash
# 持续 5 分钟, 30 并发
python document-upload-stress.py --concurrency=30 --duration=300
```

目的: 观察 Kafka consumer lag 增长趋势、内存是否泄漏、连接池是否稳定

---

## 压测指标解读

| 指标 | 含义 | 健康阈值 |
|------|------|---------|
| 上传成功率 | 202/200 响应占比 | > 95% |
| P99 上传延迟 | 99% 请求耗时 | < 5s |
| 解析延迟 P99 | 端到端 (上传→COMPLETED) | < 60s (大文件可放宽) |
| QPS | 每秒成功请求数 | 预期 > 10 req/s |
| 失败率 | HTTP 错误 + 超时占比 | < 5% |

---

## 常见问题排查

### Q: 后端连接失败
```
请确认后端服务已启动: mvn spring-boot:run
或检查 CONFIG.base_url 配置是否正确
```

### Q: Token 获取失败
```
当前脚本支持 guest 模式 (无需登录)
如需真实用户认证, 修改 CONFIG.username / CONFIG.password
```

### Q: 状态一直 PENDING / PARSING
```
说明 Kafka consumer 还未处理完:
- 检查 consumer group 消费 lag: kafka-consumer-groups.sh
- 检查 Tika 解析服务是否正常
- 查看 Spring Boot 日志中的错误信息
```

### Q: 想要测试不同文件大小
```
修改 CONFIG.file_count 或 CONFIG.file_size_mb
会自动生成不同大小的测试 PDF
```

---

## 下一步: 扩展压测场景

1. **检索接口压测** — `test-retrieval.py` (Milvus + ES + CrossEncoder)
2. **LLM 对话压测** — SSE 流式响应延迟 + Token 吞吐量
3. **限流验证** — 触发限流后排队机制是否正常工作
4. **Kafka 瓶颈定位** — 在不同并发下观察 consumer lag 变化曲线