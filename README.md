# RAGDemo

基于 LangChain4j 框架实现的 RAG（Retrieval-Augmented Generation）项目。

## 技术栈

- **Spring Boot 3.2.4** - 后端框架
- **LangChain4j 0.36.0** - LLM/Embedding 集成
- **Milvus 2.3.4** - 向量数据库
- **Elasticsearch 8.15.0** - 全文搜索引擎
- **Kafka 3.7.0** - 异步消息队列
- **MinIO** - 文件存储
- **Vue 3 + Vite** - 前端框架

## 功能特性

- 文档上传与处理
- 向量化存储与检索
- RAG 对话问答
- 知识库管理

## 项目结构

```
src/main/java/com/rag/
├── domain/           # 领域模型
│   ├── model/       # 实体类
│   └── event/       # 领域事件
├── application/     # 应用服务
├── infrastructure/  # 基础设施
│   ├── llm/        # LLM/Embedding 服务
│   ├── vector/      # 向量存储
│   ├── search/      # 搜索引擎
│   ├── storage/     # 文件存储
│   └── mq/          # 消息队列
└── api/             # REST 接口
```

## 配置说明

复制 `config.yaml.example` 为 `config.yaml` 并填写相关配置。

## 运行

```bash
# 启动后端
./mvnw spring-boot:run

# 启动前端
cd frontend && npm install && npm run dev
```
