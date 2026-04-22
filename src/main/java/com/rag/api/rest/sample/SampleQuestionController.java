package com.rag.api.rest.sample;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 示例问题管理接口（stub 实现）
 * 路径：/api/v1/sample-questions
 */
@RestController
@RequestMapping("/api/v1/sample-questions")
public class SampleQuestionController {

    public record SampleQuestion(String id, String title, String description,
                                  String question, String createTime, String updateTime) {}
    public record PageResult<T>(List<T> records, long total, int size, int current, int pages) {}
    public record Payload(String title, String description, String question) {}

    @GetMapping
    public ResponseEntity<PageResult<SampleQuestion>> listPage(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(new PageResult<>(Collections.emptyList(), 0, size, current, 0));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody Payload payload) {
        return ResponseEntity.ok(UUID.randomUUID().toString());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable String id, @RequestBody Payload payload) {
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return ResponseEntity.ok().build();
    }
}
