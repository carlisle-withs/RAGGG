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

@Component
public class MilvusVectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final MilvusClientV2 milvusClient;
    private final String collectionName;
    private final int dimension;

    public MilvusVectorStore(MilvusClientV2 milvusClient, AppConfig appConfig) {
        this.milvusClient = milvusClient;
        this.collectionName = appConfig.getMilvus().getCollection();
        this.dimension = appConfig.getEmbedding().getDimension();
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing Milvus collection: {}, dimension: {}", collectionName, dimension);

            boolean hasCollection = milvusClient.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()
            );
            log.info("Collection exists: {}", hasCollection);

            if (!hasCollection) {
                createCollection();
                log.info("Collection created successfully");
            } else {
                // Ensure collection is loaded
                milvusClient.loadCollection(
                        LoadCollectionReq.builder().collectionName(collectionName).build()
                );
                log.info("Collection already exists, loaded for use");
            }

        } catch (Exception e) {
            log.error("Failed to initialize Milvus", e);
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
        milvusClient.createCollection(createReq);
        log.info("Collection created successfully: {}", collectionName);

        // Load collection for searching
        milvusClient.loadCollection(
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
            row.addProperty("kb_id", chunk.getMetadata().getOrDefault("kbId", "").toString());

            InsertReq req = InsertReq.builder()
                    .collectionName(collectionName)
                    .data(List.of(row))
                    .build();

            log.info(">>> InsertReq built, executing insert...");
            InsertResp resp = milvusClient.insert(req);
            log.info(">>> Insert successful for chunk: {}, insertCnt={}", chunk.getId(), resp.getInsertCnt());

        } catch (Exception e) {
            log.error("Failed to insert chunk: {}", chunk.getId(), e);
            throw new RuntimeException("Insert failed", e);
        }
    }

    public List<SearchResult> search(float[] queryEmbedding, String kbId, int topK) {
        try {
            // Ensure collection is loaded before search
            milvusClient.loadCollection(
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

            SearchResp resp = milvusClient.search(searchBuilder.build());
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
