package com.rag.infrastructure.vector;

import com.rag.config.AppConfig;
import com.rag.domain.model.Chunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.*;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MilvusVectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final MilvusServiceClient milvusClient;
    private final String collectionName;
    private final int dimension;

    public MilvusVectorStore(MilvusServiceClient milvusClient, AppConfig appConfig) {
        this.milvusClient = milvusClient;
        this.collectionName = appConfig.getMilvus().getCollection();
        this.dimension = appConfig.getEmbedding().getDimension();
    }

    @PostConstruct
    public void init() {
        try {
            HasCollectionParam hasCollectionParam = HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();
            Boolean hasCollection = milvusClient.hasCollection(hasCollectionParam).getData();

            if (!hasCollection) {
                createCollection();
            }
            log.info("Milvus collection ready: {}", collectionName);
        } catch (Exception e) {
            log.error("Failed to initialize Milvus", e);
        }
    }

    private void createCollection() {
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(36)
                .withPrimaryKey(true)
                .build();

        FieldType chunkIdField = FieldType.newBuilder()
                .withName("chunk_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(36)
                .build();

        FieldType documentIdField = FieldType.newBuilder()
                .withName("document_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(36)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build();

        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build();

        FieldType kbIdField = FieldType.newBuilder()
                .withName("kb_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(36)
                .build();

        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldTypes(Arrays.asList(idField, chunkIdField, documentIdField, contentField, embeddingField, kbIdField))
                .build();

        milvusClient.createCollection(param);
        log.info("Created Milvus collection: {}", collectionName);
    }

    public void insert(Chunk chunk, float[] embedding) {
        try {
            List<InsertParam.Field> fields = Arrays.asList(
                    new InsertParam.Field("id", Collections.singletonList(chunk.getId())),
                    new InsertParam.Field("chunk_id", Collections.singletonList(chunk.getId())),
                    new InsertParam.Field("document_id", Collections.singletonList(chunk.getDocumentId())),
                    new InsertParam.Field("content", Collections.singletonList(chunk.getContent())),
                    new InsertParam.Field("embedding", Collections.singletonList(embedding)),
                    new InsertParam.Field("kb_id", Collections.singletonList(chunk.getMetadata().getOrDefault("kbId", "").toString()))
            );

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            milvusClient.insert(insertParam);
            log.debug("Inserted chunk: {}", chunk.getId());

        } catch (Exception e) {
            log.error("Failed to insert chunk: {}", chunk.getId(), e);
            throw new RuntimeException("Insert failed", e);
        }
    }

    public List<SearchResult> search(float[] queryEmbedding, String kbId, int topK) {
        try {
            SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectors(Collections.singletonList(queryEmbedding))
                    .withVectorFieldName("embedding")
                    .withTopK(topK)
                    .withConsistencyLevel(ConsistencyLevelEnum.STRONG);

            if (kbId != null && !kbId.isEmpty()) {
                searchBuilder.withExpr("kb_id == \"" + kbId + "\"");
            }

            SearchResults results = milvusClient.search(searchBuilder.build()).getData();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(results.getResults());

            List<SearchResult> searchResults = new ArrayList<>();
            List<?> records = wrapper.getRowRecords();
            for (int i = 0; i < records.size(); i++) {
                Object rowRecord = records.get(i);

                java.lang.reflect.Method getContentMethod = rowRecord.getClass().getMethod("get", String.class);
                Object contentObj = getContentMethod.invoke(rowRecord, "content");
                Object chunkIdObj = getContentMethod.invoke(rowRecord, "chunk_id");

                SearchResult result = new SearchResult();
                result.setChunkId(chunkIdObj != null ? chunkIdObj.toString() : "");
                result.setContent(contentObj != null ? contentObj.toString() : "");
                result.setScore(1.0 / (1.0 + i * 0.1));
                searchResults.add(result);
            }

            return searchResults;

        } catch (Exception e) {
            log.error("Search failed", e);
            return Collections.emptyList();
        }
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
