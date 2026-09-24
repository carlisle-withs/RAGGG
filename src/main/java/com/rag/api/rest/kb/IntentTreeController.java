package com.rag.api.rest.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.model.IntentNode;
import com.rag.domain.repository.IntentNodeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 意图树管理（P2-⑨ 落地基础版，对齐前端 intentTreeService.ts 契约）。
 * 每个意图节点可绑定知识库 collection/topK/提示词片段——
 * 检索路由消费这些配置属于后续迭代，当前先提供 CRUD 与树查询。
 */
@RestController
@RequestMapping("/api/v1/intent-tree")
public class IntentTreeController {

    private final IntentNodeRepository repository;
    private final ObjectMapper objectMapper;

    public IntentTreeController(IntentNodeRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public record NodeVO(
            Long id, String intentCode, String name, Integer level, String parentCode,
            String description, List<String> examples, String collectionName, String mcpToolId,
            Integer topK, Integer kind, Integer sortOrder, Boolean enabled,
            String promptSnippet, String promptTemplate, String paramPromptTemplate,
            List<NodeVO> children
    ) {}

    public record CreatePayload(
            String kbId, String intentCode, String name, Integer level, String parentCode,
            String description, List<String> examples, String mcpToolId, Integer topK,
            Integer kind, Integer sortOrder, Integer enabled,
            String promptSnippet, String promptTemplate, String paramPromptTemplate
    ) {}

    public record UpdatePayload(
            String name, Integer level, String parentCode, String description,
            List<String> examples, String collectionName, String mcpToolId, Integer topK,
            Integer kind, Integer sortOrder, Integer enabled,
            String promptSnippet, String promptTemplate, String paramPromptTemplate
    ) {}

    public record BatchPayload(List<Long> ids) {}

    @GetMapping("/trees")
    public ResponseEntity<List<NodeVO>> trees() {
        List<IntentNode> all = repository.findByDeletedFalseOrderByLevelAscSortOrderAsc();
        Map<String, List<NodeVO>> byParent = new LinkedHashMap<>();
        for (IntentNode n : all) {
            byParent.computeIfAbsent(n.getParentCode() == null ? "" : n.getParentCode(),
                    k -> new ArrayList<>()).add(toVO(n, new ArrayList<>()));
        }
        List<NodeVO> roots = byParent.getOrDefault("", new ArrayList<>());
        // 逐层挂孩子
        fillChildren(roots, byParent);
        return ResponseEntity.ok(roots);
    }

    private void fillChildren(List<NodeVO> nodes, Map<String, List<NodeVO>> byParent) {
        for (NodeVO node : nodes) {
            List<NodeVO> children = byParent.getOrDefault(node.intentCode(), new ArrayList<>());
            node.children().addAll(children);
            fillChildren(children, byParent);
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<String> create(@RequestBody CreatePayload p) {
        if (p.intentCode() == null || p.intentCode().isBlank()
                || p.name() == null || p.name().isBlank()) {
            return ResponseEntity.badRequest().body("intentCode/name 必填");
        }
        IntentNode n = new IntentNode();
        n.setIntentCode(p.intentCode().trim());
        n.setName(p.name());
        n.setLevel(p.level() != null ? p.level() : 1);
        n.setParentCode(p.parentCode());
        n.setDescription(p.description());
        n.setExamples(toExamplesJson(p.examples()));
        n.setMcpToolId(p.mcpToolId());
        n.setTopK(p.topK());
        n.setKind(p.kind() != null ? p.kind() : 0);
        n.setSortOrder(p.sortOrder() != null ? p.sortOrder() : 0);
        n.setEnabled(p.enabled() == null || p.enabled() != 0);
        n.setPromptSnippet(p.promptSnippet());
        n.setPromptTemplate(p.promptTemplate());
        n.setParamPromptTemplate(p.paramPromptTemplate());
        if (p.kbId() != null && !p.kbId().isBlank()) {
            try {
                n.setKbId(Long.parseLong(p.kbId()));
                // 绑定 KB 时默认带出其 collection 名，供后续检索路由使用
                n.setCollectionName("kb-" + p.kbId());
            } catch (NumberFormatException ignore) { }
        }
        return ResponseEntity.ok(String.valueOf(repository.save(n).getId()));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> update(@PathVariable Long id, @RequestBody UpdatePayload p) {
        return repository.findById(id)
                .filter(n -> !Boolean.TRUE.equals(n.getDeleted()))
                .<ResponseEntity<Void>>map(n -> {
                    if (p.name() != null) n.setName(p.name());
                    if (p.level() != null) n.setLevel(p.level());
                    if (p.parentCode() != null) n.setParentCode(p.parentCode());
                    if (p.description() != null) n.setDescription(p.description());
                    if (p.examples() != null) n.setExamples(toExamplesJson(p.examples()));
                    if (p.collectionName() != null) n.setCollectionName(p.collectionName());
                    if (p.mcpToolId() != null) n.setMcpToolId(p.mcpToolId());
                    if (p.topK() != null) n.setTopK(p.topK());
                    if (p.kind() != null) n.setKind(p.kind());
                    if (p.sortOrder() != null) n.setSortOrder(p.sortOrder());
                    if (p.enabled() != null) n.setEnabled(p.enabled() != 0);
                    if (p.promptSnippet() != null) n.setPromptSnippet(p.promptSnippet());
                    if (p.promptTemplate() != null) n.setPromptTemplate(p.promptTemplate());
                    if (p.paramPromptTemplate() != null) n.setParamPromptTemplate(p.paramPromptTemplate());
                    repository.save(n);
                    return ResponseEntity.ok().build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return repository.findById(id)
                .filter(n -> !Boolean.TRUE.equals(n.getDeleted()))
                .<ResponseEntity<Void>>map(n -> {
                    n.setDeleted(true);
                    repository.save(n);
                    return ResponseEntity.ok().build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/batch/enable")
    @Transactional
    public ResponseEntity<Integer> batchEnable(@RequestBody BatchPayload p) {
        return ResponseEntity.ok(repository.batchSetEnabled(ids(p), true));
    }

    @PostMapping("/batch/disable")
    @Transactional
    public ResponseEntity<Integer> batchDisable(@RequestBody BatchPayload p) {
        return ResponseEntity.ok(repository.batchSetEnabled(ids(p), false));
    }

    @PostMapping("/batch/delete")
    @Transactional
    public ResponseEntity<Integer> batchDelete(@RequestBody BatchPayload p) {
        return ResponseEntity.ok(repository.batchSoftDelete(ids(p)));
    }

    private List<Long> ids(BatchPayload p) {
        return p == null || p.ids() == null ? List.of() : p.ids();
    }

    private String toExamplesJson(List<String> examples) {
        if (examples == null || examples.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(examples);
        } catch (Exception e) {
            return String.join("\n", examples);
        }
    }

    private List<String> parseExamples(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private NodeVO toVO(IntentNode n, List<NodeVO> children) {
        return new NodeVO(
                n.getId(), n.getIntentCode(), n.getName(), n.getLevel(), n.getParentCode(),
                n.getDescription(), parseExamples(n.getExamples()), n.getCollectionName(),
                n.getMcpToolId(), n.getTopK(), n.getKind(), n.getSortOrder(),
                Boolean.TRUE.equals(n.getEnabled()),
                n.getPromptSnippet(), n.getPromptTemplate(), n.getParamPromptTemplate(),
                children
        );
    }
}
