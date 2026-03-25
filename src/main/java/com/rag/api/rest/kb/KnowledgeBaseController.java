package com.rag.api.rest.kb;

import com.rag.domain.model.KnowledgeBase;
import com.rag.domain.model.User;
import com.rag.domain.repository.KnowledgeBaseRepository;
import com.rag.domain.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/kbs")
public class KnowledgeBaseController {

    private final KnowledgeBaseRepository kbRepository;
    private final UserRepository userRepository;

    public KnowledgeBaseController(KnowledgeBaseRepository kbRepository, UserRepository userRepository) {
        this.kbRepository = kbRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<KnowledgeBase> kbs;
        User currentUser = getCurrentUser();

        if (isAdmin()) {
            kbs = kbRepository.findAll();
        } else {
            kbs = kbRepository.findByOwner_Id(currentUser.getId());
        }

        List<Map<String, Object>> result = kbs.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        User currentUser = getCurrentUser();

        String name = (String) request.get("name");
        String description = (String) request.getOrDefault("description", "");

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(UUID.randomUUID().toString());
        kb.setName(name);
        kb.setDescription(description);
        kb.setOwner(currentUser);
        kb.setDocumentCount(0);

        if (request.containsKey("chunkStrategy")) {
            kb.setChunkStrategy((String) request.get("chunkStrategy"));
        }
        if (request.containsKey("chunkSize")) {
            Object chunkSize = request.get("chunkSize");
            kb.setChunkSize(chunkSize instanceof Number ? ((Number) chunkSize).intValue() : Integer.parseInt(chunkSize.toString()));
        }
        if (request.containsKey("chunkOverlap")) {
            Object chunkOverlap = request.get("chunkOverlap");
            kb.setChunkOverlap(chunkOverlap instanceof Number ? ((Number) chunkOverlap).intValue() : Integer.parseInt(chunkOverlap.toString()));
        }
        if (request.containsKey("minParagraphLength")) {
            Object minPara = request.get("minParagraphLength");
            kb.setMinParagraphLength(minPara instanceof Number ? ((Number) minPara).intValue() : Integer.parseInt(minPara.toString()));
        }
        if (request.containsKey("maxParagraphLength")) {
            Object maxPara = request.get("maxParagraphLength");
            kb.setMaxParagraphLength(maxPara instanceof Number ? ((Number) maxPara).intValue() : Integer.parseInt(maxPara.toString()));
        }
        if (request.containsKey("maxTokensPerChunk")) {
            Object maxTokens = request.get("maxTokensPerChunk");
            kb.setMaxTokensPerChunk(maxTokens instanceof Number ? ((Number) maxTokens).intValue() : Integer.parseInt(maxTokens.toString()));
        }
        if (request.containsKey("similarityThreshold")) {
            Object similarity = request.get("similarityThreshold");
            kb.setSimilarityThreshold(similarity instanceof Number ? ((Number) similarity).doubleValue() : Double.parseDouble(similarity.toString()));
        }

        KnowledgeBase saved = kbRepository.save(kb);
        return ResponseEntity.status(HttpStatus.CREATED).body(toMap(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return kbRepository.findById(id)
                .map(kb -> {
                    if (!hasAccess(kb)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<Map<String, Object>>build();
                    }
                    return ResponseEntity.ok(toMap(kb));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> request) {
        return kbRepository.findById(id)
                .map(kb -> {
                    if (!hasAccess(kb)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<Map<String, Object>>build();
                    }
                    if (request.containsKey("name")) {
                        kb.setName((String) request.get("name"));
                    }
                    if (request.containsKey("description")) {
                        kb.setDescription((String) request.get("description"));
                    }
                    if (request.containsKey("chunkStrategy")) {
                        kb.setChunkStrategy((String) request.get("chunkStrategy"));
                    }
                    if (request.containsKey("chunkSize")) {
                        Object chunkSize = request.get("chunkSize");
                        kb.setChunkSize(chunkSize instanceof Number ? ((Number) chunkSize).intValue() : Integer.parseInt(chunkSize.toString()));
                    }
                    if (request.containsKey("chunkOverlap")) {
                        Object chunkOverlap = request.get("chunkOverlap");
                        kb.setChunkOverlap(chunkOverlap instanceof Number ? ((Number) chunkOverlap).intValue() : Integer.parseInt(chunkOverlap.toString()));
                    }
                    if (request.containsKey("minParagraphLength")) {
                        Object minPara = request.get("minParagraphLength");
                        kb.setMinParagraphLength(minPara instanceof Number ? ((Number) minPara).intValue() : Integer.parseInt(minPara.toString()));
                    }
                    if (request.containsKey("maxParagraphLength")) {
                        Object maxPara = request.get("maxParagraphLength");
                        kb.setMaxParagraphLength(maxPara instanceof Number ? ((Number) maxPara).intValue() : Integer.parseInt(maxPara.toString()));
                    }
                    if (request.containsKey("maxTokensPerChunk")) {
                        Object maxTokens = request.get("maxTokensPerChunk");
                        kb.setMaxTokensPerChunk(maxTokens instanceof Number ? ((Number) maxTokens).intValue() : Integer.parseInt(maxTokens.toString()));
                    }
                    if (request.containsKey("similarityThreshold")) {
                        Object similarity = request.get("similarityThreshold");
                        kb.setSimilarityThreshold(similarity instanceof Number ? ((Number) similarity).doubleValue() : Double.parseDouble(similarity.toString()));
                    }
                    KnowledgeBase saved = kbRepository.save(kb);
                    return ResponseEntity.ok(toMap(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (kbRepository.existsById(id)) {
            KnowledgeBase kb = kbRepository.findById(id).get();
            if (!hasAccess(kb)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            kbRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private boolean hasAccess(KnowledgeBase kb) {
        if (isAdmin()) return true;
        User currentUser = getCurrentUser();
        return kb.getOwner() != null && kb.getOwner().getId().equals(currentUser.getId());
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }

    private Map<String, Object> toMap(KnowledgeBase kb) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", kb.getId());
        map.put("name", kb.getName() != null ? kb.getName() : "");
        map.put("description", kb.getDescription() != null ? kb.getDescription() : "");
        map.put("ownerId", kb.getOwnerId() != null ? kb.getOwnerId() : "");
        map.put("documentCount", kb.getDocumentCount());
        map.put("createdAt", kb.getCreatedAt() != null ? kb.getCreatedAt().toString() : "");
        map.put("updatedAt", kb.getUpdatedAt() != null ? kb.getUpdatedAt().toString() : "");
        map.put("chunkStrategy", kb.getChunkStrategy() != null ? kb.getChunkStrategy() : "intelligent");
        map.put("chunkSize", kb.getChunkSize() != null ? kb.getChunkSize() : 512);
        map.put("chunkOverlap", kb.getChunkOverlap() != null ? kb.getChunkOverlap() : 50);
        map.put("minParagraphLength", kb.getMinParagraphLength() != null ? kb.getMinParagraphLength() : 50);
        map.put("maxParagraphLength", kb.getMaxParagraphLength() != null ? kb.getMaxParagraphLength() : 2000);
        map.put("maxTokensPerChunk", kb.getMaxTokensPerChunk() != null ? kb.getMaxTokensPerChunk() : 512);
        map.put("similarityThreshold", kb.getSimilarityThreshold() != null ? kb.getSimilarityThreshold() : 0.7);
        return map;
    }
}
