package com.rag.api.rest.retrieval;

import com.rag.application.retrieval.RetrievalApplicationService;
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

    public RetrievalController(RetrievalApplicationService retrievalService, KnowledgeBaseRepository kbRepository) {
        this.retrievalService = retrievalService;
        this.kbRepository = kbRepository;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> retrieve(@RequestBody RetrievalRequest request) {
        List<String> kbIds = request.kbIds();
        String kbId = (kbIds != null && !kbIds.isEmpty()) ? kbIds.get(0) : null;

        if (kbId != null && !hasKbAccess(kbId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied to knowledge base"));
        }

        List<RetrievalApplicationService.RetrievalResult> results = retrievalService.search(
                request.query(),
                kbId,
                request.topK() != null ? request.topK() : 10
        );

        return ResponseEntity.ok(Map.of(
                "results", results,
                "count", results.size()
        ));
    }

    private boolean hasKbAccess(String kbId) {
        if (isAdmin()) return true;
        User currentUser = getCurrentUser();
        try {
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

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }

    public record RetrievalRequest(String query, List<String> kbIds, Integer topK) {}
}
