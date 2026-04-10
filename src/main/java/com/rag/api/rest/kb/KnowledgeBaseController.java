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
            kbs = kbRepository.findByCreatedBy(currentUser.getUsername());
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

        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setCreatedBy(currentUser.getUsername());
        kb.setEmbeddingModel("BAAI/bge-m3");

        if (request.containsKey("embeddingModel")) {
            kb.setEmbeddingModel((String) request.get("embeddingModel"));
        }
        if (request.containsKey("chunkStrategy")) {
            kb.setChunkStrategy((String) request.get("chunkStrategy"));
        }

        KnowledgeBase saved = kbRepository.save(kb);
        return ResponseEntity.status(HttpStatus.CREATED).body(toMap(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
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
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        return kbRepository.findById(id)
                .map(kb -> {
                    if (!hasAccess(kb)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<Map<String, Object>>build();
                    }
                    if (request.containsKey("name")) {
                        kb.setName((String) request.get("name"));
                    }
                    if (request.containsKey("chunkStrategy")) {
                        kb.setChunkStrategy((String) request.get("chunkStrategy"));
                    }
                    if (request.containsKey("embeddingModel")) {
                        kb.setEmbeddingModel((String) request.get("embeddingModel"));
                    }
                    KnowledgeBase saved = kbRepository.save(kb);
                    return ResponseEntity.ok(toMap(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
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
        return kb.getCreatedBy() != null && kb.getCreatedBy().equals(currentUser.getUsername());
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
        map.put("embeddingModel", kb.getEmbeddingModel() != null ? kb.getEmbeddingModel() : "");
        map.put("collectionName", kb.getCollectionName() != null ? kb.getCollectionName() : "");
        map.put("createdBy", kb.getCreatedBy() != null ? kb.getCreatedBy() : "");
        map.put("chunkStrategy", kb.getChunkStrategy() != null ? kb.getChunkStrategy() : "intelligent");
        map.put("createdAt", kb.getCreateTime() != null ? kb.getCreateTime().toString() : "");
        map.put("updatedAt", kb.getUpdateTime() != null ? kb.getUpdateTime().toString() : "");
        return map;
    }
}
