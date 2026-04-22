package com.rag.api.rest.kb;

import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.KnowledgeBase;
import com.rag.domain.model.KnowledgeChunk;
import com.rag.domain.model.KnowledgeDocument;
import com.rag.domain.model.User;
import com.rag.domain.repository.KnowledgeBaseRepository;
import com.rag.domain.repository.KnowledgeChunkRepository;
import com.rag.domain.repository.KnowledgeDocumentRepository;
import com.rag.infrastructure.mq.DocumentEventProducer;
import com.rag.infrastructure.mq.KafkaTopics;
import com.rag.infrastructure.storage.MinioStorage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/v1/knowledge-base")
public class KnowledgeBaseController {

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeDocumentRepository docRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;

    private static final Path UPLOAD_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "rag-uploads");

    public KnowledgeBaseController(
            KnowledgeBaseRepository kbRepository,
            KnowledgeDocumentRepository docRepository,
            KnowledgeChunkRepository chunkRepository,
            MinioStorage minioStorage,
            DocumentEventProducer eventProducer) {
        this.kbRepository = kbRepository;
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        try {
            Files.createDirectories(UPLOAD_DIR);
        } catch (Exception e) {
            // ignore
        }
    }

    // ===================== 知识库 CRUD =====================

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String name) {
        List<KnowledgeBase> all = kbRepository.findAll();
        if (name != null && !name.isBlank()) {
            all = all.stream()
                    .filter(kb -> kb.getName() != null && kb.getName().contains(name))
                    .toList();
        }
        int total = all.size();
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        int from = Math.min((current - 1) * size, total);
        int to = Math.min(from + size, total);
        List<Map<String, Object>> records = from < to
                ? all.subList(from, to).stream().map(this::toKbMap).toList()
                : Collections.emptyList();
        return ok(pageMap(records, total, size, current, pages));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().build();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setCreatedBy(getCurrentUserName());
        kb.setEmbeddingModel(request.containsKey("embeddingModel")
                ? (String) request.get("embeddingModel") : "BAAI/bge-m3");
        kb.setCollectionName(request.containsKey("collectionName")
                ? (String) request.get("collectionName")
                : name.replaceAll("\\s+", "_").toLowerCase());
        if (request.containsKey("chunkStrategy")) {
            kb.setChunkStrategy((String) request.get("chunkStrategy"));
        }
        KnowledgeBase saved = kbRepository.save(kb);
        return ResponseEntity.status(HttpStatus.CREATED).body(toKbMap(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getKb(@PathVariable Long id) {
        return kbRepository.findById(id)
                .filter(kb -> hasKbAccess(kb))
                .map(kb -> ResponseEntity.ok(toKbMap(kb)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateKb(@PathVariable Long id,
                                                         @RequestBody Map<String, Object> request) {
        return kbRepository.findById(id)
                .filter(kb -> hasKbAccess(kb))
                .map(kb -> {
                    if (request.containsKey("name")) kb.setName((String) request.get("name"));
                    if (request.containsKey("chunkStrategy")) kb.setChunkStrategy((String) request.get("chunkStrategy"));
                    if (request.containsKey("embeddingModel")) kb.setEmbeddingModel((String) request.get("embeddingModel"));
                    return ResponseEntity.ok(toKbMap(kbRepository.save(kb)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteKb(@PathVariable Long id) {
        return kbRepository.findById(id)
                .filter(kb -> hasKbAccess(kb))
                .map(kb -> {
                    kbRepository.deleteById(id);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/chunk-strategies")
    public ResponseEntity<List<Map<String, Object>>> chunkStrategies() {
        List<Map<String, Object>> strategies = List.of(
                Map.of("value", "intelligent", "label", "智能分块",
                        "defaultConfig", Map.of("chunkSize", 512, "overlapSize", 50)),
                Map.of("value", "naive", "label", "朴素分块",
                        "defaultConfig", Map.of("chunkSize", 300, "overlapSize", 0)),
                Map.of("value", "recursive", "label", "递归字符分块",
                        "defaultConfig", Map.of("chunkSize", 256, "overlapSize", 20)),
                Map.of("value", "semantic", "label", "语义分块",
                        "defaultConfig", Map.of("chunkSize", 384, "overlapSize", 50)),
                Map.of("value", "document", "label", "文档级分块",
                        "defaultConfig", Map.of("chunkSize", 0, "overlapSize", 0))
        );
        return ResponseEntity.ok(strategies);
    }

    // ===================== 文档管理 =====================

    @GetMapping("/{kbId}/docs")
    public ResponseEntity<Map<String, Object>> listDocs(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        if (!kbRepository.existsById(kbId)) return ResponseEntity.notFound().build();
        List<KnowledgeDocument> all = docRepository.findAll().stream()
                .filter(d -> d.getKbId().equals(kbId) && !Boolean.TRUE.equals(d.getDeleted()))
                .filter(d -> status == null || status.isBlank() ||
                        d.getStatus().name().equalsIgnoreCase(status))
                .filter(d -> keyword == null || keyword.isBlank() ||
                        (d.getDocName() != null && d.getDocName().contains(keyword)))
                .sorted(Comparator.comparing(KnowledgeDocument::getCreateTime).reversed())
                .toList();
        int total = all.size();
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        int from = Math.min((current - 1) * size, total);
        int to = Math.min(from + size, total);
        List<Map<String, Object>> records = from < to
                ? all.subList(from, to).stream().map(this::toDocMap).toList()
                : Collections.emptyList();
        return ok(pageMap(records, total, size, current, pages));
    }

    @PostMapping("/{kbId}/docs/upload")
    public ResponseEntity<Map<String, Object>> uploadDoc(
            @PathVariable Long kbId,
            @RequestParam String sourceType,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String sourceLocation,
            @RequestParam(required = false) Boolean scheduleEnabled,
            @RequestParam(required = false) String scheduleCron,
            @RequestParam(required = false) String processMode,
            @RequestParam(required = false) String chunkStrategy,
            @RequestParam(required = false) String chunkConfig,
            @RequestParam(required = false) String pipelineId) {
        if (!kbRepository.existsById(kbId)) return ResponseEntity.notFound().build();
        if ("file".equalsIgnoreCase(sourceType) && (file == null || file.isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择文件"));
        }
        if ("url".equalsIgnoreCase(sourceType) && (sourceLocation == null || sourceLocation.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入URL地址"));
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setSourceType(sourceType);
        doc.setProcessMode(processMode != null ? processMode : "chunk");
        doc.setChunkStrategy(chunkStrategy);
        doc.setChunkConfig(chunkConfig);
        doc.setScheduleEnabled(scheduleEnabled);
        doc.setScheduleCron(scheduleCron);
        doc.setCreatedBy(getCurrentUserName());
        doc.setStatus(KnowledgeDocument.DocumentStatus.PENDING);
        doc.setEnabled(true);

        String minioPath = null;
        String docName = null;
        String fileType = null;

        if ("file".equalsIgnoreCase(sourceType) && file != null) {
            docName = file.getOriginalFilename();
            fileType = file.getContentType();
            doc.setFileSize(file.getSize());
            doc.setDocName(docName != null ? docName : "未命名文件");
            doc.setFileType(fileType != null ? fileType.split("/")[1].toUpperCase() : "FILE");

            // 上传到 MinIO
            String ext = docName != null && docName.contains(".") ? docName.substring(docName.lastIndexOf(".")) : "";
            doc.setFileUrl("minio://" + minioPath); // 先占位，后面用真实路径
            KnowledgeDocument saved = docRepository.save(doc);
            minioPath = kbId + "/" + saved.getId() + "/" + docName;
            saved.setFileUrl(minioPath);
            docRepository.save(saved);

            try {
                minioStorage.upload(minioPath, file);
                doc = saved;
            } catch (Exception e) {
                doc.setStatus(KnowledgeDocument.DocumentStatus.FAILED);
                docRepository.save(doc);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "MinIO上传失败: " + e.getMessage()));
            }
        } else {
            doc.setSourceLocation(sourceLocation);
            docName = sourceLocation != null ? (sourceLocation.length() > 50 ? sourceLocation.substring(0, 50) : sourceLocation) : "远程文档";
            doc.setDocName(docName);
            doc.setFileType("URL");
            doc.setFileSize(0L);
            minioPath = ""; // URL 类型暂不上传 MinIO
            KnowledgeDocument saved = docRepository.save(doc);
            doc = saved;
        }

        // 发 Kafka 消息，触发 ParseService -> ChunkService -> IndexService 链路
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunkStrategy", chunkStrategy != null ? chunkStrategy : "intelligent");
        metadata.put("fileSize", doc.getFileSize());
        if (chunkConfig != null) metadata.put("chunkConfig", chunkConfig);

        DocumentEvent event = DocumentEvent.create(
                String.valueOf(doc.getId()),
                String.valueOf(kbId),
                docName,
                fileType,
                minioPath,
                metadata
        );
        eventProducer.sendUploaded(event);

return ResponseEntity.status(HttpStatus.CREATED).body(toDocMap(doc));
    }

    @PostMapping("/{kbId}/docs/batch-upload")
    public ResponseEntity<Map<String, Object>> batchUpload(
            @PathVariable Long kbId,
            @RequestParam String sourceType,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(required = false) String processMode,
            @RequestParam(required = false) String chunkStrategy,
            @RequestParam(required = false) String chunkConfig) {
        if (!kbRepository.existsById(kbId)) return ResponseEntity.notFound().build();
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "no files provided"));
        }
        List<Map<String, Object>> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            try {
                String fileName = file.getOriginalFilename();
                String fileType = file.getContentType();

                // 先创建 Document 记录，拿到 DB 自增 ID
                String docName = fileName != null ? fileName : "未命名文件";
                String fType = fileType != null ? fileType.split("/")[1].toUpperCase() : "FILE";
                String fileUrlPlaceholder = "minio://pending/" + docName;

                KnowledgeDocument doc = new KnowledgeDocument();
                doc.setKbId(kbId);
                doc.setSourceType("file");
                doc.setDocName(docName);
                doc.setFileType(fType);
                doc.setFileSize(file.getSize());
                doc.setFileUrl(fileUrlPlaceholder);
                doc.setProcessMode(processMode != null ? processMode : "chunk");
                doc.setChunkStrategy(chunkStrategy);
                doc.setChunkConfig(chunkConfig);
                doc.setStatus(KnowledgeDocument.DocumentStatus.PENDING);
                doc.setEnabled(true);
                doc.setCreatedBy(getCurrentUserName());
                KnowledgeDocument saved = docRepository.save(doc);

                // 上传到 MinIO
                String minioPath = kbId + "/" + saved.getId() + "/" + fileName;
                saved.setFileUrl(minioPath);
                docRepository.save(saved);
                minioStorage.upload(minioPath, file);

                // 发 Kafka 消息
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("chunkStrategy", chunkStrategy != null ? chunkStrategy : "intelligent");
                metadata.put("fileSize", file.getSize());
                if (chunkConfig != null) metadata.put("chunkConfig", chunkConfig);

                DocumentEvent event = DocumentEvent.create(
                        String.valueOf(saved.getId()),
                        String.valueOf(kbId),
                        fileName,
                        fileType,
                        minioPath,
                        metadata
                );
                eventProducer.sendUploaded(event);

                succeeded.add(toDocMap(saved));
            } catch (Exception e) {
                failed.add(Map.of("name", file.getOriginalFilename(), "error", e.getMessage()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", files.length);
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        result.put("succeededCount", succeeded.size());
        result.put("failedCount", failed.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/docs/{docId}")
    public ResponseEntity<Map<String, Object>> getDoc(@PathVariable Long docId) {
        return docRepository.findById(docId)
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .map(d -> ResponseEntity.ok(toDocMap(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/docs/{docId}")
    public ResponseEntity<Map<String, Object>> updateDoc(
            @PathVariable Long docId,
            @RequestBody Map<String, Object> request) {
        return docRepository.findById(docId)
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .map(doc -> {
                    if (request.containsKey("docName")) doc.setDocName((String) request.get("docName"));
                    if (request.containsKey("processMode")) doc.setProcessMode((String) request.get("processMode"));
                    if (request.containsKey("chunkStrategy")) doc.setChunkStrategy((String) request.get("chunkStrategy"));
                    if (request.containsKey("chunkConfig")) doc.setChunkConfig((String) request.get("chunkConfig"));
                    if (request.containsKey("pipelineId")) {
                        Object pid = request.get("pipelineId");
                        doc.setPipelineId(pid != null ? Long.valueOf(pid.toString()) : null);
                    }
                    if (request.containsKey("sourceLocation")) doc.setSourceLocation((String) request.get("sourceLocation"));
                    if (request.containsKey("scheduleEnabled")) doc.setScheduleEnabled((Boolean) request.get("scheduleEnabled"));
                    if (request.containsKey("scheduleCron")) doc.setScheduleCron((String) request.get("scheduleCron"));
                    doc.setUpdatedBy(getCurrentUserName());
                    return ResponseEntity.ok(toDocMap(docRepository.save(doc)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/docs/{docId}/chunk")
    public ResponseEntity<Map<String, Object>> startChunk(@PathVariable Long docId) {
        return docRepository.findById(docId)
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .map(doc -> {
                    doc.setStatus(KnowledgeDocument.DocumentStatus.PROCESSING);
                    doc.setUpdatedBy(getCurrentUserName());
                    docRepository.save(doc);
                    // stub: 实际异步处理，状态保持 PROCESSING
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("message", "分块任务已提交");
                    resp.put("docId", docId);
                    return ResponseEntity.ok(resp);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/docs/{docId}/enable")
    public ResponseEntity<Void> enableDoc(@PathVariable Long docId, @RequestParam boolean value) {
        return docRepository.findById(docId)
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .map(doc -> {
                    doc.setEnabled(value);
                    doc.setUpdatedBy(getCurrentUserName());
                    docRepository.save(doc);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/docs/{docId}")
    public ResponseEntity<Void> deleteDoc(@PathVariable Long docId) {
        return docRepository.findById(docId)
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .map(doc -> {
                    doc.setDeleted(true);
                    doc.setUpdatedBy(getCurrentUserName());
                    docRepository.save(doc);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/docs/{docId}/chunk-logs")
    public ResponseEntity<Map<String, Object>> chunkLogs(
            @PathVariable Long docId,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        // stub: 返回空分页
        Map<String, Object> page = pageMap(Collections.emptyList(), 0, size, current, 0);
        return ResponseEntity.ok(page);
    }

    // ===================== 文档块管理 =====================

    @GetMapping("/docs/{docId}/chunks")
    public ResponseEntity<Map<String, Object>> listChunks(
            @PathVariable Long docId,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer enabled) {
        List<KnowledgeChunk> all = chunkRepository.findAll().stream()
                .filter(c -> c.getDocId().equals(docId) && !Boolean.TRUE.equals(c.getDeleted()))
                .filter(c -> enabled == null || enabled == 1 == Boolean.TRUE.equals(c.getEnabled()))
                .toList();
        int total = all.size();
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        int from = Math.min((current - 1) * size, total);
        int to = Math.min(from + size, total);
        List<Map<String, Object>> records = from < to
                ? all.subList(from, to).stream().map(this::toChunkMap).toList()
                : Collections.emptyList();
        return ok(pageMap(records, total, size, current, pages));
    }

    @PostMapping("/docs/{docId}/chunks")
    public ResponseEntity<Map<String, Object>> createChunk(
            @PathVariable Long docId,
            @RequestBody Map<String, Object> request) {
        return docRepository.findById(docId)
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .map(doc -> {
                    KnowledgeChunk chunk = new KnowledgeChunk();
                    chunk.setDocId(docId);
                    chunk.setKbId(doc.getKbId());
                    chunk.setContent((String) request.getOrDefault("content", ""));
                    Object idx = request.get("index");
                    chunk.setChunkIndex(idx != null ? ((Number) idx).intValue() : 0);
                    chunk.setCharCount(chunk.getContent() != null ? chunk.getContent().length() : 0);
                    chunk.setEnabled(true);
                    chunk.setCreatedBy(getCurrentUserName());
                    KnowledgeChunk saved = chunkRepository.save(chunk);
                    // 更新文档 chunkCount
                    long count = chunkRepository.findAll().stream()
                            .filter(c -> c.getDocId().equals(docId) && !Boolean.TRUE.equals(c.getDeleted()))
                            .count();
                    doc.setChunkCount((int) count);
                    doc.setStatus(KnowledgeDocument.DocumentStatus.CHUNKED);
                    docRepository.save(doc);
                    return ResponseEntity.status(HttpStatus.CREATED).body(toChunkMap(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/docs/{docId}/chunks/{chunkId}")
    public ResponseEntity<Map<String, Object>> updateChunk(
            @PathVariable Long docId,
            @PathVariable Long chunkId,
            @RequestBody Map<String, Object> request) {
        return chunkRepository.findById(chunkId)
                .filter(c -> c.getDocId().equals(docId) && !Boolean.TRUE.equals(c.getDeleted()))
                .map(chunk -> {
                    if (request.containsKey("content")) {
                        chunk.setContent((String) request.get("content"));
                        chunk.setCharCount(chunk.getContent() != null ? chunk.getContent().length() : 0);
                    }
                    chunk.setUpdatedBy(getCurrentUserName());
                    return ResponseEntity.ok(toChunkMap(chunkRepository.save(chunk)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/docs/{docId}/chunks/{chunkId}")
    public ResponseEntity<Void> deleteChunk(@PathVariable Long docId, @PathVariable Long chunkId) {
        return chunkRepository.findById(chunkId)
                .filter(c -> c.getDocId().equals(docId) && !Boolean.TRUE.equals(c.getDeleted()))
                .map(chunk -> {
                    chunk.setDeleted(true);
                    chunk.setUpdatedBy(getCurrentUserName());
                    chunkRepository.save(chunk);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/docs/{docId}/chunks/{chunkId}/enable")
    public ResponseEntity<Void> toggleChunk(@PathVariable Long docId, @PathVariable Long chunkId,
                                             @RequestParam boolean value) {
        return chunkRepository.findById(chunkId)
                .filter(c -> c.getDocId().equals(docId) && !Boolean.TRUE.equals(c.getDeleted()))
                .map(chunk -> {
                    chunk.setEnabled(value);
                    chunk.setUpdatedBy(getCurrentUserName());
                    chunkRepository.save(chunk);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/docs/{docId}/chunks/batch-enable")
    public ResponseEntity<Void> batchToggleChunks(
            @PathVariable Long docId,
            @RequestParam boolean value,
            @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) request.get("chunkIds");
        if (ids == null) return ResponseEntity.badRequest().build();
        for (Number idNum : ids) {
            chunkRepository.findById(idNum.longValue())
                    .filter(c -> c.getDocId().equals(docId) && !Boolean.TRUE.equals(c.getDeleted()))
                    .ifPresent(chunk -> {
                        chunk.setEnabled(value);
                        chunk.setUpdatedBy(getCurrentUserName());
                        chunkRepository.save(chunk);
                    });
        }
        return ResponseEntity.ok().build();
    }

    // ===================== 批量重索引 =====================

    /**
     * 触发知识库下所有文档重新索引（仅重做向量化入库，不重做解析/分块）。
     * 利用 MinIO 中已有的 chunks.json 文件，直接发送 CHUNKED 事件到 Kafka。
     */
    @PostMapping("/{kbId}/reindex")
    public ResponseEntity<Map<String, Object>> reindexKb(@PathVariable Long kbId) {
        if (!kbRepository.existsById(kbId)) {
            return ResponseEntity.notFound().build();
        }

        List<KnowledgeDocument> docs = docRepository.findAll().stream()
                .filter(d -> d.getKbId().equals(kbId) && !Boolean.TRUE.equals(d.getDeleted()))
                .toList();

        int triggered = 0;
        for (KnowledgeDocument doc : docs) {
            try {
                String chunksMinioPath = kbId + "/" + doc.getId() + "/chunks.json";
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("chunksMinioPath", chunksMinioPath);
                metadata.put("reindex", true);

                DocumentEvent event = DocumentEvent.create(
                        String.valueOf(doc.getId()),
                        String.valueOf(kbId),
                        doc.getDocName() != null ? doc.getDocName() : "",
                        doc.getFileType() != null ? doc.getFileType() : "",
                        doc.getFileUrl() != null ? doc.getFileUrl() : "",
                        metadata
                );
                event.setEventType(DocumentEvent.EventType.CHUNKED);
                event.setTraceId(java.util.UUID.randomUUID().toString());
                eventProducer.sendChunked(event);
                triggered++;
            } catch (Exception e) {
                // 跳过失败文档
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kbId", kbId);
        resp.put("totalDocs", docs.size());
        resp.put("triggered", triggered);
        resp.put("message", "已发送 " + triggered + " 条重索引消息到 Kafka");
        return ResponseEntity.ok(resp);
    }

    // ===================== 文档搜索 =====================

    @GetMapping("/docs/search")
    public ResponseEntity<List<Map<String, Object>>> searchDocs(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "8") int limit) {
        List<KnowledgeDocument> all = docRepository.findAll().stream()
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .filter(d -> d.getDocName() != null && d.getDocName().contains(keyword))
                .limit(limit)
                .toList();
        List<Map<String, Object>> results = all.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("kbId", d.getKbId());
            m.put("docName", d.getDocName());
            return m;
        }).toList();
        return ResponseEntity.ok(results);
    }

    // ===================== private helpers =====================

    private boolean hasKbAccess(KnowledgeBase kb) {
        if (isAdmin()) return true;
        if (getCurrentUser() == null) return true;
        return kb.getCreatedBy() != null && kb.getCreatedBy().equals(getCurrentUserName());
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) return null;
        if (auth.getPrincipal() instanceof User u) return u;
        return null;
    }

    private String getCurrentUserName() {
        User user = getCurrentUser();
        return user != null ? user.getUsername() : "guest";
    }

    private Map<String, Object> toKbMap(KnowledgeBase kb) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", String.valueOf(kb.getId()));
        map.put("name", kb.getName() != null ? kb.getName() : "");
        map.put("embeddingModel", kb.getEmbeddingModel() != null ? kb.getEmbeddingModel() : "");
        map.put("collectionName", kb.getCollectionName() != null ? kb.getCollectionName() : "");
        map.put("createdBy", kb.getCreatedBy() != null ? kb.getCreatedBy() : "");
        map.put("chunkStrategy", kb.getChunkStrategy() != null ? kb.getChunkStrategy() : "intelligent");
        map.put("createTime", kb.getCreateTime() != null ? kb.getCreateTime().toString() : "");
        map.put("updateTime", kb.getUpdateTime() != null ? kb.getUpdateTime().toString() : "");
        // documentCount 从文档表统计
        long docCount = docRepository.findAll().stream()
                .filter(d -> d.getKbId().equals(kb.getId()) && !Boolean.TRUE.equals(d.getDeleted()))
                .count();
        map.put("documentCount", docCount);
        return map;
    }

    private Map<String, Object> toDocMap(KnowledgeDocument doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", String.valueOf(doc.getId()));
        map.put("kbId", String.valueOf(doc.getKbId()));
        map.put("docName", doc.getDocName() != null ? doc.getDocName() : "");
        map.put("sourceType", doc.getSourceType() != null ? doc.getSourceType() : "");
        map.put("sourceLocation", doc.getSourceLocation() != null ? doc.getSourceLocation() : "");
        map.put("scheduleEnabled", doc.getScheduleEnabled());
        map.put("scheduleCron", doc.getScheduleCron() != null ? doc.getScheduleCron() : "");
        map.put("enabled", doc.getEnabled());
        map.put("chunkCount", doc.getChunkCount() != null ? doc.getChunkCount() : 0);
        map.put("fileUrl", doc.getFileUrl() != null ? doc.getFileUrl() : "");
        map.put("fileType", doc.getFileType() != null ? doc.getFileType() : "");
        map.put("fileSize", doc.getFileSize() != null ? doc.getFileSize() : 0);
        map.put("processMode", doc.getProcessMode() != null ? doc.getProcessMode() : "chunk");
        map.put("chunkStrategy", doc.getChunkStrategy() != null ? doc.getChunkStrategy() : "");
        map.put("chunkConfig", doc.getChunkConfig() != null ? doc.getChunkConfig() : "");
        map.put("pipelineId", doc.getPipelineId());
        map.put("status", doc.getStatus() != null ? doc.getStatus().name().toLowerCase() : "pending");
        map.put("createdBy", doc.getCreatedBy() != null ? doc.getCreatedBy() : "");
        map.put("updatedBy", doc.getUpdatedBy() != null ? doc.getUpdatedBy() : "");
        map.put("createTime", doc.getCreateTime() != null ? doc.getCreateTime().toString() : "");
        map.put("updateTime", doc.getUpdateTime() != null ? doc.getUpdateTime().toString() : "");
        return map;
    }

    private Map<String, Object> toChunkMap(KnowledgeChunk chunk) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", String.valueOf(chunk.getId()));
        map.put("kbId", String.valueOf(chunk.getKbId()));
        map.put("docId", String.valueOf(chunk.getDocId()));
        map.put("chunkIndex", chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0);
        map.put("content", chunk.getContent() != null ? chunk.getContent() : "");
        map.put("contentHash", chunk.getContentHash() != null ? chunk.getContentHash() : "");
        map.put("charCount", chunk.getCharCount() != null ? chunk.getCharCount() : 0);
        map.put("tokenCount", chunk.getTokenCount() != null ? chunk.getTokenCount() : 0);
        map.put("enabled", chunk.getEnabled());
        map.put("createTime", chunk.getCreateTime() != null ? chunk.getCreateTime().toString() : "");
        map.put("updateTime", chunk.getUpdateTime() != null ? chunk.getUpdateTime().toString() : "");
        return map;
    }

    private Map<String, Object> pageMap(List<?> records, int total, int size, int current, int pages) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", records);
        page.put("total", total);
        page.put("size", size);
        page.put("current", current);
        page.put("pages", pages);
        return page;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }
}
