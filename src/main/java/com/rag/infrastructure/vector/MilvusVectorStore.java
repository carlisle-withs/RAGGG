package com.rag.infrastructure.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rag.config.AppConfig;
import com.rag.domain.model.Chunk;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;

@Component
public class MilvusVectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final Supplier<MilvusClientV2> milvusClientSupplier;
    private MilvusClientV2 milvusClient;
    private final String collectionName;
    private final int dimension;

    public MilvusVectorStore(ObjectProvider<Supplier<MilvusClientV2>> milvusClientSupplierProvider, AppConfig appConfig) {
        this.milvusClientSupplier = milvusClientSupplierProvider.getObject();
        this.collectionName = appConfig.getMilvus().getCollection();
        this.dimension = appConfig.getEmbedding().getDimension();
    }

    private MilvusClientV2 milvusClient() {
        if (milvusClient == null) {
            milvusClient = milvusClientSupplier.get();
        }
        return milvusClient;
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing Milvus collection: {}, dimension: {}", collectionName, dimension);

            boolean hasCollection = milvusClient().hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()
            );
            log.info("Collection exists: {}", hasCollection);

            if (!hasCollection) {
                createCollection();
                log.info("Collection created successfully");
            } else {
                // 维度校验：如果已有 Collection 维度与配置不符，自动重建
                int existingDim = getExistingCollectionDimension();
                if (existingDim > 0 && existingDim != dimension) {
                    log.warn("Collection dimension mismatch! configured={}, existing={} — dropping and recreating...",
                            dimension, existingDim);
                    dropCollection();
                    createCollection();
                    log.info("Collection recreated with correct dimension: {}", dimension);
                } else {
                    // Ensure collection is loaded
                    milvusClient().loadCollection(
                            LoadCollectionReq.builder().collectionName(collectionName).build()
                    );
                    log.info("Collection already exists (dimension={}), loaded for use", existingDim);
                }
            }

        } catch (Exception e) {
            log.warn("Milvus collection initialization deferred (Milvus may be unavailable): {}", e.getMessage());
        }
    }

    private int getExistingCollectionDimension() {
        // 通过查询一条已有记录来获取向量维度
        try {
            var queryResp = milvusClient().query(
                    io.milvus.v2.service.vector.request.QueryReq.builder()
                            .collectionName(collectionName)
                            .outputFields(java.util.List.of("embedding"))
                            .limit(1)
                            .build()
            );
            var queryResults = queryResp.getQueryResults();
            if (queryResults != null && !queryResults.isEmpty()) {
                var entity = queryResults.get(0).getEntity();
                var embedding = (java.util.List<?>) entity.get("embedding");
                if (embedding != null) {
                    return embedding.size();
                }
            }
        } catch (Exception e) {
            log.debug("Could not get existing collection dimension via query: {}", e.getMessage());
        }
        return -1;
    }

    private void dropCollection() {
        try {
            milvusClient().dropCollection(
                    io.milvus.v2.service.collection.request.DropCollectionReq
                            .builder().collectionName(collectionName).build()
            );
            log.info("Collection dropped: {}", collectionName);
            // 等待 Collection 完全删除
            Thread.sleep(500);
        } catch (Exception e) {
            log.warn("Drop collection failed (may already be deleted): {}", e.getMessage());
        }
    }

    private void createCollection() {
        List<CreateCollectionReq.FieldSchema> fieldSchemaList = new ArrayList<>();

        // Primary key field - doc_id (String)
        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("doc_id")
                        .dataType(DataType.VarChar)
                        .maxLength(36)
                        .isPrimaryKey(true)
                        .autoID(false)
                        .build()
        );

        // chunk_id field
        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("chunk_id")
                        .dataType(DataType.VarChar)
                        .maxLength(36)
                        .build()
        );

        // document_id field
        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("document_id")
                        .dataType(DataType.VarChar)
                        .maxLength(36)
                        .build()
        );

        // content field
        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("content")
                        .dataType(DataType.VarChar)
                        .maxLength(65535)
                        .build()
        );

        // embedding field
        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("embedding")
                        .dataType(DataType.FloatVector)
                        .dimension(dimension)
                        .build()
        );

        // kb_id field
        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("kb_id")
                        .dataType(DataType.VarChar)
                        .maxLength(36)
                        .build()
        );

        CreateCollectionReq.CollectionSchema collectionSchema = CreateCollectionReq.CollectionSchema
                .builder()
                .fieldSchemaList(fieldSchemaList)
                .build();

        // Index on embedding field
        IndexParam hnswIndex = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.IP)
                .indexName("embedding")
                .extraParams(Map.of(
                        "M", "16",
                        "efConstruction", "200"
                ))
                .build();

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(collectionSchema)
                .primaryFieldName("doc_id")
                .vectorFieldName("embedding")
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .indexParams(List.of(hnswIndex))
                .build();

        log.info("Creating collection with fields: doc_id(VarChar,PK), chunk_id(VarChar), document_id(VarChar), content(VarChar), embedding(FloatVector,dim={}), kb_id(VarChar)", dimension);
        milvusClient().createCollection(createReq);
        log.info("Collection created successfully: {}", collectionName);

        // Load collection for searching
        milvusClient().loadCollection(
                LoadCollectionReq.builder().collectionName(collectionName).build()
        );
        log.info("Milvus collection loaded: {}", collectionName);
    }

    public void insert(Chunk chunk, float[] embedding) {
        try {
            log.info(">>> About to insert to Milvus: id={}, collection={}, embedding_len={}",
                    chunk.getId(), collectionName, embedding.length);

            JsonObject row = new JsonObject();
            row.addProperty("doc_id", chunk.getId());
            row.addProperty("chunk_id", chunk.getId());
            row.addProperty("document_id", chunk.getDocumentId());
            row.addProperty("content", chunk.getContent());
            row.add("embedding", toJsonArray(embedding));
            row.addProperty("kb_id", chunk.getKbId() != null ? chunk.getKbId() : "");

            InsertReq req = InsertReq.builder()
                    .collectionName(collectionName)
                    .data(List.of(row))
                    .build();

            log.info(">>> InsertReq built, executing insert...");
            InsertResp resp = milvusClient().insert(req);
            log.info(">>> Insert successful for chunk: {}, insertCnt={}", chunk.getId(), resp.getInsertCnt());

        } catch (Exception e) {
            log.error("Failed to insert chunk: {}", chunk.getId(), e);
            throw new RuntimeException("Insert failed", e);
        }
    }

    /**
     * 批量插入：将一个文档的所有 chunks + 向量一次 RPC 请求写入 Milvus。
     * @param chunksAndEmbeddings List of (Chunk, float[]) pairs
     */
    public void insertBatch(List<Map.Entry<Chunk, float[]>> chunksAndEmbeddings) {
        if (chunksAndEmbeddings == null || chunksAndEmbeddings.isEmpty()) return;
        try {
            List<JsonObject> rows = new ArrayList<>(chunksAndEmbeddings.size());
            for (Map.Entry<Chunk, float[]> entry : chunksAndEmbeddings) {
                Chunk chunk = entry.getKey();
                float[] embedding = entry.getValue();
                JsonObject row = new JsonObject();
                row.addProperty("doc_id", chunk.getId());
                row.addProperty("chunk_id", chunk.getId());
                row.addProperty("document_id", chunk.getDocumentId());
                // Milvus VARCHAR 上限 65535，截断超长 content 防止插入失败
                String content = chunk.getContent();
                if (content != null && content.length() > 60000) {
                    content = content.substring(0, 60000);
                    log.warn("Content truncated to 60000 chars for chunk: {}", chunk.getId());
                }
                row.addProperty("content", content);
                row.add("embedding", toJsonArray(embedding));
                row.addProperty("kb_id", chunk.getKbId() != null ? chunk.getKbId() : "");
                rows.add(row);
            }
            InsertReq req = InsertReq.builder()
                    .collectionName(collectionName)
                    .data(rows)
                    .build();
            InsertResp resp = milvusClient().insert(req);
            log.debug("Batch inserted {} chunks to Milvus, insertCnt={}",
                    chunksAndEmbeddings.size(), resp.getInsertCnt());
        } catch (Exception e) {
            log.error("Batch insert to Milvus failed for {} chunks", chunksAndEmbeddings.size(), e);
            throw new RuntimeException("Milvus batch insert failed", e);
        }
    }

    public List<SearchResult> search(float[] queryEmbedding, String kbId, int topK) {
        try {
            // Ensure collection is loaded before search
            milvusClient().loadCollection(
                    LoadCollectionReq.builder().collectionName(collectionName).build()
            );

            // Convert float[] to List<BaseVector>
            List<BaseVector> vectors = List.of(new FloatVec(queryEmbedding));

            Map<String, Object> params = new HashMap<>();
            params.put("metric_type", "IP");
            params.put("ef", 128);

            SearchReq.SearchReqBuilder searchBuilder = SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField("embedding")
                    .data(vectors)
                    .topK(topK)
                    .searchParams(params)
                    .outputFields(List.of("doc_id", "chunk_id", "content"));

            if (kbId != null && !kbId.isEmpty()) {
                searchBuilder.filter("kb_id == \"" + kbId + "\"");
            }

            SearchResp resp = milvusClient().search(searchBuilder.build());
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

            if (results == null || results.isEmpty()) {
                return Collections.emptyList();
            }

            List<SearchResult> searchResults = new ArrayList<>();
            int i = 0;
            for (SearchResp.SearchResult r : results.get(0)) {
                Map<String, Object> entity = r.getEntity();
                SearchResult result = new SearchResult();
                result.setChunkId(entity.get("chunk_id") != null ? entity.get("chunk_id").toString() : "");
                result.setContent(entity.get("content") != null ? entity.get("content").toString() : "");
                result.setScore(r.getScore());
                searchResults.add(result);
                i++;
            }

            return searchResults;

        } catch (Exception e) {
            log.error("Search failed", e);
            return Collections.emptyList();
        }
    }

    private JsonArray toJsonArray(float[] v) {
        JsonArray arr = new JsonArray(v.length);
        for (float x : v) {
            arr.add(x);
        }
        return arr;
    }

    public static class SearchResult {
        private String chunkId;
        private String content;
        private double score;

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }
}
