package com.rag.api.rest.retrieval;

import com.rag.application.retrieval.RetrievalApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/retrieve")
public class RetrievalController {

    private final RetrievalApplicationService retrievalService;

    public RetrievalController(RetrievalApplicationService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> retrieve(@RequestBody RetrievalRequest request) {
        List<String> kbIds = request.kbIds();
        String kbId = (kbIds != null && !kbIds.isEmpty()) ? kbIds.get(0) : null;

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

    public record RetrievalRequest(String query, List<String> kbIds, Integer topK) {}
}
