package com.rag.api.rest.ingestion;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/ingestion")
public class IngestionController {

    @GetMapping("/pipelines")
    public ResponseEntity<Map<String, Object>> listPipelines(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String keyword) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", Collections.emptyList());
        page.put("total", 0);
        page.put("size", size);
        page.put("current", current);
        page.put("pages", 0);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/pipelines/{id}")
    public ResponseEntity<Map<String, Object>> getPipeline(@PathVariable Long id) {
        return ResponseEntity.notFound().build();
    }
}
