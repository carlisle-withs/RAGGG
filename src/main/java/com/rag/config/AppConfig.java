package com.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "")
public class AppConfig {

    private App app = new App();
    private Kafka kafka = new Kafka();
    private Milvus milvus = new Milvus();
    private Elasticsearch elasticsearch = new Elasticsearch();
    private Mysql mysql = new Mysql();
    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    private Chunking chunking = new Chunking();
    private Minio minio = new Minio();

    public static class App {
        private String host = "0.0.0.0";
        private int port = 8080;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private Consumer consumer = new Consumer();
        public static class Consumer {
            private String groupId = "rag-system";
            public String getGroupId() { return groupId; }
            public void setGroupId(String groupId) { this.groupId = groupId; }
        }
        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
        public Consumer getConsumer() { return consumer; }
        public void setConsumer(Consumer consumer) { this.consumer = consumer; }
    }

    public static class Milvus {
        private String uri = "http://localhost:19530";
        private String collection = "rag_chunks";
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
    }

    public static class Elasticsearch {
        private String host = "localhost";
        private int port = 9201;
        private String index = "rag_documents";
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getIndex() { return index; }
        public void setIndex(String index) { this.index = index; }
    }

    public static class Mysql {
        private String host = "localhost";
        private int port = 3307;
        private String database = "rag_system";
        private String username = "root";
        private String password = "password";
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Llm {
        private String provider = "minimax";  // openai, minimax
        private String apiKey = "";
        private String model = "MiniMax-Text-01";
        private String baseUrl = "https://api.minimax.chat/v1";
        private String groupId = "";  // MiniMax specific
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
    }

    public static class Embedding {
        private String model = "BAAI/bge-m3";
        private int dimension = 1024;
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey = "";
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    public static class Chunking {
        private int chunkSize = 512;
        private int chunkOverlap = 50;
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    }

    public static class Minio {
        private String endpoint = "http://localhost:9001";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "rag-documents";
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
    }

    public App getApp() { return app; }
    public void setApp(App app) { this.app = app; }
    public Kafka getKafka() { return kafka; }
    public void setKafka(Kafka kafka) { this.kafka = kafka; }
    public Milvus getMilvus() { return milvus; }
    public void setMilvus(Milvus milvus) { this.milvus = milvus; }
    public Elasticsearch getElasticsearch() { return elasticsearch; }
    public void setElasticsearch(Elasticsearch elasticsearch) { this.elasticsearch = elasticsearch; }
    public Mysql getMysql() { return mysql; }
    public void setMysql(Mysql mysql) { this.mysql = mysql; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Chunking getChunking() { return chunking; }
    public void setChunking(Chunking chunking) { this.chunking = chunking; }
    public Minio getMinio() { return minio; }
    public void setMinio(Minio minio) { this.minio = minio; }
}
