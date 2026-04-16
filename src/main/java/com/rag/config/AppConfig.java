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
    private Extraction extraction = new Extraction();

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
    public Extraction getExtraction() { return extraction; }
    public void setExtraction(Extraction extraction) { this.extraction = extraction; }

    // ===== P1: Reranker 配置 =====
    private Reranker reranker = new Reranker();

    // ===== P1: 检索策略配置 =====
    private Retrieval retrieval = new Retrieval();

    // ===== P1: OpenTelemetry 配置 =====
    private Otel otel = new Otel();

    // ===== P1: SWA 分块策略配置 =====
    private SwaChunking swaChunking = new SwaChunking();

    private React react = new React();

    // ===== P1: Reranker 配置类 =====
    public static class Reranker {
        private boolean enabled = true;
        private String provider = "siliconflow";  // siliconflow / cohere / bi_encoder
        private String model = "BAAI/bge-reranker-v2-m3";
        private Siliconflow siliconflow = new Siliconflow();
        private Cohere cohere = new Cohere();

        public static class Siliconflow {
            private boolean enabled = false;
            private String apiKey = "";
            private String model = "BAAI/bge-reranker-v2-m3";
            private String baseUrl = "https://api.siliconflow.cn/v1";
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        }

        public static class Cohere {
            private String apiKey = "";
            private String model = "rerank-multilingual-v3.0";
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Siliconflow getSiliconflow() { return siliconflow; }
        public void setSiliconflow(Siliconflow siliconflow) { this.siliconflow = siliconflow; }
        public Cohere getCohere() { return cohere; }
        public void setCohere(Cohere cohere) { this.cohere = cohere; }
    }

    // ===== P1: 检索策略配置类 =====
    public static class Retrieval {
        private String strategy = "hybrid";  // hybrid / hierarchical
        private Rerank rerank = new Rerank();
        private int initialTopk = 20;
        private int finalTopk = 5;
        private Swaswa swa = new Swaswa();

        public static class Rerank {
            private boolean enabled = true;
            private String provider = "siliconflow";
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getProvider() { return provider; }
            public void setProvider(String provider) { this.provider = provider; }
        }

        public static class Swaswa {
            private double mergingRatio = 0.5;
            private int windowSize = 3;
            public double getMergingRatio() { return mergingRatio; }
            public void setMergingRatio(double mergingRatio) { this.mergingRatio = mergingRatio; }
            public int getWindowSize() { return windowSize; }
            public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
        }

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public Rerank getRerank() { return rerank; }
        public void setRerank(Rerank rerank) { this.rerank = rerank; }
        public int getInitialTopk() { return initialTopk; }
        public void setInitialTopk(int initialTopk) { this.initialTopk = initialTopk; }
        public int getFinalTopk() { return finalTopk; }
        public void setFinalTopk(int finalTopk) { this.finalTopk = finalTopk; }
        public Swaswa getSwa() { return swa; }
        public void setSwa(Swaswa swa) { this.swa = swa; }
    }

    // ===== P1: OpenTelemetry 配置类 =====
    public static class Otel {
        private boolean enabled = false;
        private Service service = new Service();
        private Exporter exporter = new Exporter();
        private Metrics metrics = new Metrics();
        private Resource resource = new Resource();
        private Sampling sampling = new Sampling();

        public static class Service {
            private String name = "raggg";
            private String version = "1.0.0";
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getVersion() { return version; }
            public void setVersion(String version) { this.version = version; }
        }

        public static class Exporter {
            private String endpoint = "http://localhost:4317";
            public String getEndpoint() { return endpoint; }
            public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        }

        public static class Metrics {
            private int intervalSeconds = 30;
            public int getIntervalSeconds() { return intervalSeconds; }
            public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
        }

        public static class Resource {
            private String env = "dev";
            public String getEnv() { return env; }
            public void setEnv(String env) { this.env = env; }
        }

        public static class Sampling {
            private double ratio = 1.0;
            public double getRatio() { return ratio; }
            public void setRatio(double ratio) { this.ratio = ratio; }
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Service getService() { return service; }
        public void setService(Service service) { this.service = service; }
        public Exporter getExporter() { return exporter; }
        public void setExporter(Exporter exporter) { this.exporter = exporter; }
        public Metrics getMetrics() { return metrics; }
        public void setMetrics(Metrics metrics) { this.metrics = metrics; }
        public Resource getResource() { return resource; }
        public void setResource(Resource resource) { this.resource = resource; }
        public Sampling getSampling() { return sampling; }
        public void setSampling(Sampling sampling) { this.sampling = sampling; }
    }

    // ===== P1: SWA 分块策略配置类 =====
    public static class SwaChunking {
        private int leafChunkSize = 128;
        private int leafChunkOverlap = 20;
        private int parentChunkSize = 512;
        private int windowSize = 3;
        public int getLeafChunkSize() { return leafChunkSize; }
        public void setLeafChunkSize(int leafChunkSize) { this.leafChunkSize = leafChunkSize; }
        public int getLeafChunkOverlap() { return leafChunkOverlap; }
        public void setLeafChunkOverlap(int leafChunkOverlap) { this.leafChunkOverlap = leafChunkOverlap; }
        public int getParentChunkSize() { return parentChunkSize; }
        public void setParentChunkSize(int parentChunkSize) { this.parentChunkSize = parentChunkSize; }
        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
    }

    public Reranker getReranker() { return reranker; }
    public void setReranker(Reranker reranker) { this.reranker = reranker; }

    public Retrieval getRetrieval() { return retrieval; }
    public void setRetrieval(Retrieval retrieval) { this.retrieval = retrieval; }

    public Otel getOtel() { return otel; }
    public void setOtel(Otel otel) { this.otel = otel; }

    public SwaChunking getSwaChunking() { return swaChunking; }
    public void setSwaChunking(SwaChunking swaChunking) { this.swaChunking = swaChunking; }

    public React getReact() { return react; }
    public void setReact(React react) { this.react = react; }

    public static class Extraction {
        private boolean enabled = false;
        private Image image = new Image();
        private Table table = new Table();
        private Processing processing = new Processing();

        public static class Image {
            private boolean enabled = true;
            private int minSize = 50;
            private Ocr ocr = new Ocr();

            public static class Ocr {
                private String provider = "aliyun";
                private String apiKey = "";
                private String apiSecret = "";
                private String region = "cn-shanghai";
                private String language = "ZH-CN";

                public String getProvider() { return provider; }
                public void setProvider(String provider) { this.provider = provider; }
                public String getApiKey() { return apiKey; }
                public void setApiKey(String apiKey) { this.apiKey = apiKey; }
                public String getApiSecret() { return apiSecret; }
                public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
                public String getRegion() { return region; }
                public void setRegion(String region) { this.region = region; }
                public String getLanguage() { return language; }
                public void setLanguage(String language) { this.language = language; }
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public int getMinSize() { return minSize; }
            public void setMinSize(int minSize) { this.minSize = minSize; }
            public Ocr getOcr() { return ocr; }
            public void setOcr(Ocr ocr) { this.ocr = ocr; }
        }

        public static class Table {
            private boolean enabled = true;
            private Parser parser = new Parser();

            public static class Parser {
                private String provider = "aliyun";
                private String apiKey = "";
                private String apiSecret = "";
                private String region = "cn-shanghai";
                private String outputFormat = "html";

                public String getProvider() { return provider; }
                public void setProvider(String provider) { this.provider = provider; }
                public String getApiKey() { return apiKey; }
                public void setApiKey(String apiKey) { this.apiKey = apiKey; }
                public String getApiSecret() { return apiSecret; }
                public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
                public String getRegion() { return region; }
                public void setRegion(String region) { this.region = region; }
                public String getOutputFormat() { return outputFormat; }
                public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public Parser getParser() { return parser; }
            public void setParser(Parser parser) { this.parser = parser; }
        }

        public static class Processing {
            private boolean parallel = true;
            private int maxConcurrency = 4;
            private int timeoutSeconds = 30;
            private int retryCount = 2;

            public boolean isParallel() { return parallel; }
            public void setParallel(boolean parallel) { this.parallel = parallel; }
            public int getMaxConcurrency() { return maxConcurrency; }
            public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
            public int getTimeoutSeconds() { return timeoutSeconds; }
            public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
            public int getRetryCount() { return retryCount; }
            public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Image getImage() { return image; }
        public void setImage(Image image) { this.image = image; }
        public Table getTable() { return table; }
        public void setTable(Table table) { this.table = table; }
        public Processing getProcessing() { return processing; }
        public void setProcessing(Processing processing) { this.processing = processing; }
    }

    public static class React {
        private boolean enabled = false;
        private Router router = new Router();
        private Actions actions = new Actions();
        private Cache cache = new Cache();
        private LoopDetection loopDetection = new LoopDetection();
        private LightweightLlm lightweightLlm = new LightweightLlm();

        public static class Router {
            private boolean enabled = true;
            private String model = "Qwen2-1.5B";
            private int timeoutMs = 100;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
            public int getTimeoutMs() { return timeoutMs; }
            public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        }

        public static class Actions {
            private Knowledge knowledge = new Knowledge();
            private Database database = new Database();
            private Conversation conversation = new Conversation();

            public static class Knowledge {
                private boolean enabled = true;
                private int topK = 5;

                public boolean isEnabled() { return enabled; }
                public void setEnabled(boolean enabled) { this.enabled = enabled; }
                public int getTopK() { return topK; }
                public void setTopK(int topK) { this.topK = topK; }
            }

            public static class Database {
                private boolean enabled = true;

                public boolean isEnabled() { return enabled; }
                public void setEnabled(boolean enabled) { this.enabled = enabled; }
            }

            public static class Conversation {
                private boolean enabled = true;
                private int windowSize = 10;

                public boolean isEnabled() { return enabled; }
                public void setEnabled(boolean enabled) { this.enabled = enabled; }
                public int getWindowSize() { return windowSize; }
                public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
            }

            public Knowledge getKnowledge() { return knowledge; }
            public void setKnowledge(Knowledge knowledge) { this.knowledge = knowledge; }
            public Database getDatabase() { return database; }
            public void setDatabase(Database database) { this.database = database; }
            public Conversation getConversation() { return conversation; }
            public void setConversation(Conversation conversation) { this.conversation = conversation; }
        }

        public static class Cache {
            private boolean enabled = true;
            private SimilarityThreshold similarityThreshold = new SimilarityThreshold();

            public static class SimilarityThreshold {
                private float directHit = 0.95f;
                private float review = 0.85f;

                public float getDirectHit() { return directHit; }
                public void setDirectHit(float directHit) { this.directHit = directHit; }
                public float getReview() { return review; }
                public void setReview(float review) { this.review = review; }
            }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public SimilarityThreshold getSimilarityThreshold() { return similarityThreshold; }
            public void setSimilarityThreshold(SimilarityThreshold similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        }

        public static class LoopDetection {
            private boolean enabled = true;
            private int maxIterations = 5;
            private int fingerprintMatchCount = 3;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public int getMaxIterations() { return maxIterations; }
            public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
            public int getFingerprintMatchCount() { return fingerprintMatchCount; }
            public void setFingerprintMatchCount(int fingerprintMatchCount) { this.fingerprintMatchCount = fingerprintMatchCount; }
        }

        public static class LightweightLlm {
            private String model = "Qwen2-1.5B";
            private String provider = "local";
            private String baseUrl = "http://localhost:8081/v1";
            private String apiKey = "dummy";
            private int timeoutMs = 10000;

            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
            public String getProvider() { return provider; }
            public void setProvider(String provider) { this.provider = provider; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public int getTimeoutMs() { return timeoutMs; }
            public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Router getRouter() { return router; }
        public void setRouter(Router router) { this.router = router; }
        public Actions getActions() { return actions; }
        public void setActions(Actions actions) { this.actions = actions; }
        public Cache getCache() { return cache; }
        public void setCache(Cache cache) { this.cache = cache; }
        public LoopDetection getLoopDetection() { return loopDetection; }
        public void setLoopDetection(LoopDetection loopDetection) { this.loopDetection = loopDetection; }
        public LightweightLlm getLightweightLlm() { return lightweightLlm; }
        public void setLightweightLlm(LightweightLlm lightweightLlm) { this.lightweightLlm = lightweightLlm; }
    }
}
