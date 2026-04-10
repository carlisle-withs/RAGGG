# 快速入门

## 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | JDK 17 LTS |
| Maven | 3.8+ | 构建工具 |
| MySQL | 8.0+ | 关系数据库 |
| Redis | 7+ | 缓存/会话存储 |
| Milvus | 2.6+ | 向量数据库 |
| Elasticsearch | 8.x | 全文搜索引擎 |
| MinIO | - | 对象存储 |
| Kafka | 3.x | 消息队列 |

---

## 快速启动

### 1. 克隆项目

```bash
git clone <repository-url>
cd RAGGG
```

### 2. 配置数据库

创建 `config.yaml` 或修改 `application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ragent?useSSL=false&serverTimezone=UTC
    username: root
    password: your_password
  
  data:
    redis:
      host: localhost
      port: 6379
```

### 3. 启动基础设施

使用 Docker Compose 启动所有中间件：

```bash
docker-compose up -d
```

### 4. 编译并运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/rag-demo-1.0.0-SNAPSHOT.jar
```

### 5. 访问系统

- API 地址: http://localhost:8080/api/v1
- 默认管理员: admin / admin123

---

## 核心 API

### 认证

```bash
# 登录
POST /api/v1/auth/login
{
  "username": "admin",
  "password": "admin123"
}
```

### 知识库

```bash
# 创建知识库
POST /api/v1/kbs
{
  "name": "我的知识库",
  "embeddingModel": "BAAI/bge-m3"
}

# 上传文档
POST /api/v1/documents/upload
Content-Type: multipart/form-data

kbId: 1
file: <your-file.pdf>
```

### 对话

```bash
# 聊天
POST /api/v1/chat
{
  "message": "你好，请介绍一下RAG技术",
  "kbIds": ["1"]
}
```

---

## 下一步

- 查看 [架构文档](../architecture/01-architecture-overview.md) 了解系统设计
- 查看 [数据库设计](../database/02-database-design.md) 了解数据模型
- 查看 [部署文档](./02-deployment.md) 了解生产环境部署

---

*最后更新: 2026-04-10*
