package com.rag.domain.repository;

import com.rag.domain.model.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    List<KnowledgeDocument> findByKbId(Long kbId);
    List<KnowledgeDocument> findByCreatedBy(String createdBy);
    List<KnowledgeDocument> findByStatus(String status);
    List<KnowledgeDocument> findByDeletedFalse();
    List<KnowledgeDocument> findByKbIdAndDeletedFalse(Long kbId);
}
