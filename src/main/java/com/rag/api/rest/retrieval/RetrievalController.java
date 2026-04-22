package com.rag.api.rest.retrieval;

import com.rag.application.retrieval.RetrievalApplicationService;
import com.rag.application.retrieval.RetrievalMetrics;
import com.rag.domain.model.User;
import com.rag.domain.repository.KnowledgeBaseRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/retrieve")
public class RetrievalController {

    private final RetrievalApplicationService retrievalService;
    private final KnowledgeBaseRepository kbRepository;
    private final RetrievalMetrics retrievalMetrics;

    public RetrievalController(RetrievalApplicationService retrievalService,
                               KnowledgeBaseRepository kbRepository,
                               RetrievalMetrics retrievalMetrics) {
        this.retrievalService = retrievalService;
        this.kbRepository = kbRepository;
        this.retrievalMetrics = retrievalMetrics;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> retrieve(@RequestBody RetrievalRequest request) {
        List<String> kbIds = request.kbIds();
        String kbId = (kbIds != null && !kbIds.isEmpty()) ? kbIds.get(0) : null;

        if (kbId != null && !hasKbAccess(kbId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied to knowledge base"));
        }

        long startMs = System.currentTimeMillis();
        int topK = request.topK() != null ? request.topK() : 10;
        boolean doRerank = request.rerank() != null ? request.rerank() : false;
        String mode = doRerank ? "vector+rerank" : "vector";

        List<RetrievalApplicationService.RetrievalResult> results;
        try {
            if (doRerank) {
                results = retrievalService.searchWithRerank(request.query(), kbId, topK);
            } else {
                results = retrievalService.search(request.query(), kbId, topK);
            }
        } catch (Exception e) {
            retrievalMetrics.recordError(mode, "exception");
            throw e;
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        retrievalMetrics.recordRequest(mode);
        retrievalMetrics.recordLatency(mode, elapsedMs);
        retrievalMetrics.recordResultCount(mode, results.size());

        return ResponseEntity.ok(Map.of(
                "results", results,
                "count", results.size(),
                "latencyMs", elapsedMs
        ));
    }

    @PostMapping("/hybrid")
    public ResponseEntity<Map<String, Object>> hybridRetrieve(@RequestBody HybridRetrievalRequest request) {
        List<String> kbIds = request.kbIds();
        String kbId = (kbIds != null && !kbIds.isEmpty()) ? kbIds.get(0) : null;

        if (kbId != null && !hasKbAccess(kbId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied to knowledge base"));
        }

        long startMs = System.currentTimeMillis();
        boolean doRerank = request.rerank() != null ? request.rerank() : true;
        String mode = doRerank ? "hybrid+rerank" : "hybrid";

        List<RetrievalApplicationService.RetrievalResult> results;
        try {
            results = retrievalService.hybridSearch(request.query(), kbId,
                    request.topK() != null ? request.topK() : 10, doRerank);
        } catch (Exception e) {
            retrievalMetrics.recordError(mode, "exception");
            throw e;
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        retrievalMetrics.recordRequest(mode);
        retrievalMetrics.recordLatency(mode, elapsedMs);
        retrievalMetrics.recordResultCount(mode, results.size());

        return ResponseEntity.ok(Map.of(
                "results", results,
                "count", results.size(),
                "mode", "hybrid",
                "latencyMs", elapsedMs
        ));
    }

    private boolean hasKbAccess(String kbId) {
        if (isAdmin()) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getPrincipal())) {
            return true;
        }
        try {
            User currentUser = (User) auth.getPrincipal();
            Long id = Long.parseLong(kbId);
            return kbRepository.findById(id)
                    .map(kb -> kb.getCreatedBy() != null && kb.getCreatedBy().equals(currentUser.getUsername()))
                    .orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    public record RetrievalRequest(String query, List<String> kbIds, Integer topK, Boolean rerank) {}
    public record HybridRetrievalRequest(String query, List<String> kbIds, Integer topK, Boolean rerank) {}
}
