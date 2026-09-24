package com.rag.api.rest.kb;

import com.rag.application.chat.QueryTermService;
import com.rag.domain.model.QueryTermMapping;
import com.rag.domain.repository.QueryTermMappingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 查询词映射管理（P2-⑨ 落地，对齐前端 queryTermMappingService.ts 契约）。
 * 规则在检索前由 QueryTermService 做术语归一化（"向量库"→"向量数据库"）。
 */
@RestController
@RequestMapping("/api/v1/mappings")
public class QueryTermMappingController {

    private final QueryTermMappingRepository repository;
    private final QueryTermService queryTermService;

    public QueryTermMappingController(QueryTermMappingRepository repository,
                                      QueryTermService queryTermService) {
        this.repository = repository;
        this.queryTermService = queryTermService;
    }

    public record PageResult<T>(java.util.List<T> records, long total, int size, int current, int pages) {}

    public record MappingPayload(String sourceTerm, String targetTerm, Integer matchType,
                                 Integer priority, Boolean enabled, String remark) {}

    public record MappingVO(String id, String sourceTerm, String targetTerm, int matchType,
                            int priority, boolean enabled, String remark,
                            String createTime, String updateTime) {}

    @GetMapping
    public ResponseEntity<PageResult<MappingVO>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        PageRequest pageable = PageRequest.of(Math.max(0, current - 1), Math.max(1, size),
                Sort.by(Sort.Direction.DESC, "updateTime"));
        Page<QueryTermMapping> page = repository.search(
                keyword == null || keyword.isBlank() ? null : keyword, pageable);
        return ResponseEntity.ok(new PageResult<>(
                page.getContent().stream().map(this::toVO).toList(),
                page.getTotalElements(), page.getSize(), current, page.getTotalPages()));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody MappingPayload payload) {
        if (isBlank(payload.sourceTerm()) || isBlank(payload.targetTerm())) {
            return ResponseEntity.badRequest().body("sourceTerm/targetTerm 必填");
        }
        QueryTermMapping m = new QueryTermMapping();
        applyPayload(m, payload);
        m = repository.save(m);
        queryTermService.invalidateCache();
        return ResponseEntity.ok(String.valueOf(m.getId()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable Long id, @RequestBody MappingPayload payload) {
        return repository.findById(id)
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .<ResponseEntity<Void>>map(m -> {
                    applyPayload(m, payload);
                    repository.save(m);
                    queryTermService.invalidateCache();
                    return ResponseEntity.ok().build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return repository.findById(id)
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .<ResponseEntity<Void>>map(m -> {
                    m.setDeleted(true);
                    repository.save(m);
                    queryTermService.invalidateCache();
                    return ResponseEntity.ok().build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 调试：预览某个查询经术语归一化后的结果 */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, String>> preview(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        return ResponseEntity.ok(Map.of("original", query, "mapped", queryTermService.applyMappings(query)));
    }

    private void applyPayload(QueryTermMapping m, MappingPayload p) {
        if (!isBlank(p.sourceTerm())) m.setSourceTerm(p.sourceTerm().trim());
        if (!isBlank(p.targetTerm())) m.setTargetTerm(p.targetTerm().trim());
        if (p.matchType() != null) m.setMatchType(p.matchType());
        if (p.priority() != null) m.setPriority(p.priority());
        if (p.enabled() != null) m.setEnabled(p.enabled());
        if (p.remark() != null) m.setRemark(p.remark());
    }

    private MappingVO toVO(QueryTermMapping m) {
        return new MappingVO(
                String.valueOf(m.getId()),
                m.getSourceTerm(), m.getTargetTerm(),
                m.getMatchType() != null ? m.getMatchType() : 1,
                m.getPriority() != null ? m.getPriority() : 100,
                Boolean.TRUE.equals(m.getEnabled()),
                m.getRemark(),
                m.getCreateTime() != null ? m.getCreateTime().toString() : null,
                m.getUpdateTime() != null ? m.getUpdateTime().toString() : null
        );
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
