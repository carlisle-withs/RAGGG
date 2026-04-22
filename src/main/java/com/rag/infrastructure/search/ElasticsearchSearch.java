package com.rag.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.rag.config.AppConfig;
import com.rag.domain.model.Chunk;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class ElasticsearchSearch {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchSearch.class);

    private final ElasticsearchClient client;
    private final String indexName;

    public ElasticsearchSearch(ElasticsearchClient client, AppConfig appConfig) {
        this.client = client;
        this.indexName = appConfig.getElasticsearch().getIndex();
    }

    @PostConstruct
    public void init() {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                client.indices().create(c -> c
                        .index(indexName)
                        .mappings(m -> m
                                .properties("id", p -> p.keyword(k -> k))
                                .properties("content", p -> p.text(t -> t))
                                .properties("document_id", p -> p.keyword(k -> k))
                                .properties("kb_id", p -> p.keyword(k -> k))
                                .properties("created_at", p -> p.date(d -> d))
                        )
                );
                log.info("Created Elasticsearch index: {}", indexName);
            }
        } catch (Exception e) {
            log.warn("Elasticsearch index check/create deferred: {} — will retry on first use", e.getMessage());
        }
    }

    public void index(Chunk chunk) {
        try {
            Map<String, Object> document = new HashMap<>();
            document.put("id", chunk.getId());
            document.put("content", chunk.getContent());
            document.put("document_id", chunk.getDocumentId());
            document.put("kb_id", chunk.getKbId() != null ? chunk.getKbId() : "");
            document.put("metadata", chunk.getMetadata());
            document.put("created_at", chunk.getCreatedAt().toString());

            client.index(IndexRequest.of(i -> i
                    .index(indexName)
                    .id(chunk.getId())
                    .document(document)
            ));
            log.debug("Indexed chunk: {}", chunk.getId());
        } catch (IOException e) {
            log.error("Failed to index chunk: {}", chunk.getId(), e);
            throw new RuntimeException("Index failed", e);
        }
    }

    /**
     * 批量索引：将一个文档的所有 chunks 一次Bulk请求写入 ES。
     */
    public void indexBatch(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (Chunk chunk : chunks) {
                Map<String, Object> document = new HashMap<>();
                document.put("id", chunk.getId());
                document.put("content", chunk.getContent());
                document.put("document_id", chunk.getDocumentId());
                document.put("kb_id", chunk.getKbId() != null ? chunk.getKbId() : "");
                document.put("metadata", chunk.getMetadata());
                document.put("created_at", chunk.getCreatedAt().toString());

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(indexName)
                                .id(chunk.getId())
                                .document(document)
                        )
                );
            }
            var resp = client.bulk(bulkBuilder.build());
            if (resp.errors()) {
                log.error("Bulk ES index had errors: {}", resp.items().stream()
                        .filter(i -> i.error() != null)
                        .findFirst()
                        .map(i -> i.error().reason())
                        .orElse("unknown"));
            }
            log.debug("Bulk indexed {} chunks", chunks.size());
        } catch (IOException e) {
            log.error("Bulk index failed for {} chunks", chunks.size(), e);
            throw new RuntimeException("Bulk index failed", e);
        }
    }

    public List<SearchResult> search(String query, String kbId, int topK) {
        try {
            SearchResponse<Map> response = client.search(s -> s
                            .index(indexName)
                            .query(q -> q
                                    .bool(b -> {
                                        b.must(m -> m.multiMatch(mm -> mm.query(query).fields("content")));
                                        if (kbId != null && !kbId.isEmpty()) {
                                            b.filter(f -> f.term(t -> t.field("kb_id").value(kbId)));
                                        }
                                        return b;
                                    })
                            )
                            .size(topK),
                    Map.class
            );

            List<SearchResult> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                SearchResult result = new SearchResult();
                result.setId(hit.source().get("id").toString());
                result.setContent(hit.source().get("content").toString());
                result.setScore(hit.score() != null ? hit.score() : 0.0);
                results.add(result);
            }
            return results;

        } catch (IOException e) {
            log.error("Search failed", e);
            return Collections.emptyList();
        }
    }

    public void delete(String chunkId) {
        try {
            client.delete(d -> d.index(indexName).id(chunkId));
        } catch (IOException e) {
            log.error("Failed to delete chunk: {}", chunkId, e);
        }
    }

    public static class SearchResult {
        private String id;
        private String content;
        private double score;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }
}
