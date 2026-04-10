# 部署文档

## 部署架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         Load Balancer                            │
│                      (Nginx / 云负载均衡)                         │
└─────────────────────────────────┬───────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Application Servers                          │
│                   ┌─────────┐  ┌─────────┐  ┌─────────┐       │
│                   │ Server1 │  │ Server2 │  │ Server3 │       │
│                   │ (8080)  │  │ (8080)  │  │ (8080)  │       │
│                   └─────────┘  └─────────┘  └─────────┘       │
└─────────────────────────────────┬───────────────────────────────┘
                                  │
        ┌─────────────────────────┼─────────────────────────┐
        │                         │                         │
        ▼                         ▼                         ▼
┌───────────────┐       ┌───────────────┐       ┌───────────────┐
│    MySQL      │       │    Redis      │       │   MinIO       │
│   (主从复制)   │       │   (集群)      │       │   (对象存储)   │
└───────────────┘       └───────────────┘       └───────────────┘
        │
        ▼
┌───────────────┐       ┌───────────────┐       ┌───────────────┐
│   Milvus      │       │Elasticsearch │       │    Kafka      │
│  (向量数据库)   │       │  (全文搜索)   │       │   (消息队列)   │
└───────────────┘       └───────────────┘       └───────────────┘
```

---

## 环境准备

### 1. 安装 Java 17

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-17-jdk

# CentOS/RHEL
sudo yum install java-17-openjdk
```

### 2. 安装 Maven

```bash
# Ubuntu/Debian
sudo apt install maven

# 下载安装
wget https://dlcdn.apache.org/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz
tar -xzf apache-maven-3.9.6-bin.tar.gz
export PATH=$PATH:/opt/apache-maven-3.9.6/bin
```

---

## Docker Compose 部署

### 1. 创建 docker-compose.yml

```yaml
version: '3.8'

services:
  mysql:
    image: mysql:8.4.0
    environment:
      MYSQL_ROOT_PASSWORD: your_password
      MYSQL_DATABASE: ragent
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
    command: --default-authentication-plugin=mysql_native_password

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

  minio:
    image: minio/minio
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    volumes:
      - minio_data:/data
    command: server /data --console-address ":9001"

  elasticsearch:
    image: elasticsearch:8.15.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - "ES_JAVA_OPTS=-Xms2g -Xmx2g"
    ports:
      - "9200:9200"
    volumes:
      - es_data:/usr/share/elasticsearch/data

  milvus:
    image: milvusdb/milvus:v2.6.6
    ports:
      - "19530:19530"
    volumes:
      - milvus_data:/var/lib/milvus

  kafka:
    image: confluentinc/cp-kafka:3.7.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
    depends_on:
      - zookeeper

  zookeeper:
    image: confluentinc/cp-zookeeper:3.7.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

volumes:
  mysql_data:
  redis_data:
  minio_data:
  es_data:
  milvus_data:
```

### 2. 启动服务

```bash
docker-compose up -d
```

---

## 应用部署

### 1. 构建 JAR

```bash
mvn clean package -DskipTests
```

### 2. 创建启动脚本

```bash
#!/bin/bash
java -jar \
  -Xms2g -Xmx4g \
  -Dspring.profiles.active=prod \
  -Dserver.port=8080 \
  rag-demo-1.0.0-SNAPSHOT.jar
```

### 3. 配置示例 (application-prod.yml)

```yaml
spring:
  datasource:
    url: jdbc:mysql://mysql:3306/ragent?useSSL=false&serverTimezone=UTC
    username: root
    password: ${DB_PASSWORD}
  
  redis:
    host: redis
    port: 6379

  kafka:
    bootstrap-servers: kafka:9092

milvus:
  uri: http://milvus:19530

elasticsearch:
  host: elasticsearch
  port: 9200

minio:
  endpoint: http://minio:9000
  access-key: minioadmin
  secret-key: minioadmin

jwt:
  secret: your-256-bit-secret-key-here-must-be-long-enough
  expiration: 86400000
```

---

## Nginx 配置

```nginx
upstream rag_backend {
    server 127.0.0.1:8080;
    server 127.0.0.1:8081;
    server 127.0.0.1:8082;
}

server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://rag_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

---

## 性能调优

### JVM 参数

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| -Xms | 2g | 初始堆大小 |
| -Xmx | 4g | 最大堆大小 |
| -XX:+UseG1GC | - | 使用 G1 垃圾回收器 |

### Kafka 分区

```yaml
# 推荐配置
partitions: 6
replication-factor: 3
```

### Milvus 索引

```yaml
index_type: HNSW
metric_type: COSINE
params:
  M: 16
  efConstruction: 200
```

---

## 监控

### Prometheus 配置

```yaml
scrape_configs:
  - job_name: 'rag-demo'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

### Grafana Dashboard

推荐监控指标：
- JVM 内存使用
- HTTP 请求延迟
- Kafka 消费 lag
- Milvus 查询 QPS

---

## 备份策略

### MySQL

```bash
# 每日备份
mysqldump -u root -p ragent > backup_$(date +%Y%m%d).sql
```

### Redis

```bash
# RDB 持久化备份
redis-cli SAVE
```

---

## 下一步

- 查看 [快速入门](./01-quick-start.md) 了解开发环境
- 查看 [架构文档](../architecture/01-architecture-overview.md) 了解系统设计

---

*最后更新: 2026-04-10*
