# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RAGDemo is a production RAG (Retrieval-Augmented Generation) system built with Spring Boot 3.2.4 (Java 17) and LangChain4J 0.36.0. It provides intelligent QA, document retrieval, and knowledge base management.

## Build & Run Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName

# Run with specific config
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.config.additional-location=file:./config.yaml"

# Docker infrastructure (from project root)
docker-compose up -d
```

## Architecture

### Layer Structure
```
src/main/java/com/rag/
├── api/rest/          # REST Controllers (Auth, Chat, Document, KB, Retrieval, Evaluation)
├── application/       # Application Services (Chat, Document, Retrieval, Evaluation)
├── domain/           # Domain Models & Business Rules
│   ├── model/        # Entities (User, Role, Permission, Document, Chunk, KnowledgeBase)
│   ├── repository/   # JPA Repositories
│   ├── event/        # Domain Events (DocumentEvent)
│   └── chunking/     # Chunk Strategies (Fixed, Structural, Semantic, Intelligent)
└── infrastructure/  # External Service Integrations
    ├── llm/          # ChatModelService, EmbeddingService, CrossEncoderReranker
    ├── mq/           # Kafka consumers (ParseService, ChunkService, IndexService)
    ├── storage/      # MinIO
    ├── vector/       # Milvus VectorStore
    └── search/       # Elasticsearch
```

### Key Design Patterns

**1. Strategy Pattern for Chunking**
- `ChunkStrategy` interface defines `chunk()` method
- `ChunkStrategyFactory` selects strategy based on document type
- Strategies: `FixedChunkStrategy`, `StructuralChunkStrategy`, `SemanticChunkStrategy`, `IntelligentChunkingStrategy`

**2. Kafka Async Pipeline**
- Document processing is fully async via Kafka topics: `document-raw` → `document-chunked` → `document-indexed`
- `DocumentEventProducer` publishes events; consumers process each stage
- MySQL status updated at each stage transition

**3. Hybrid Retrieval with RRF Fusion**
- `HybridRetrievalService` combines Milvus (vector) + Elasticsearch (BM25)
- Uses RRF (Reciprocal Rank Fusion): `score = Σ 1/(k + rank_i)` with k=60
- `CrossEncoderReranker` does final precision ranking

**4. Memory Management**
- `MemoryService` implements sliding window (configurable window size)
- Auto-summary via LLM when threshold reached (default 8 turns)
- Redis for hot data (TTL 7 days), MySQL for persistent summaries

**5. Intent Classification**
- `IntentClassifier` categorizes queries: factual, opinion, instruction, chitchat, clarification
- Routes to RAG pipeline or闲聊回复 accordingly

## Dependencies

| Component | Technology |
|-----------|------------|
| LLM Framework | LangChain4J 0.36.0 |
| Vector DB | Milvus 2.6.6 |
| Full-text Search | Elasticsearch 8.15.0 |
| Message Queue | Apache Kafka 3.7.0 |
| Object Storage | MinIO |
| Relational DB | MySQL 8.4.0 |
| Cache | Redis 7 |
| Document Parsing | Apache Tika 2.9.2 |
| Auth | Spring Security + JWT (jjwt 0.12.5) |

## Configuration

All settings via `config.yaml` (see `config.yaml.example`):
- `server.ip` - centralize IP configuration
- LLM: provider, api-key, model, base-url, group-id
- Embedding: model, dimension, base-url, api-key
- Reranker: model
- Storage: milvus uri, elasticsearch host/port, mysql, redis, minio
- Chunking: chunk-size, chunk-overlap
- Memory: window-size, summary-threshold, ttl-days

## Default Credentials
- Admin: `admin` / `admin123`
- API: `http://localhost:8080` (or configured port)
- Frontend: `http://localhost:8080/index.html`
