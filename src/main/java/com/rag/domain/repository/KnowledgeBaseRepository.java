package com.rag.domain.repository;

import com.rag.domain.model.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    List<KnowledgeBase> findByCreatedBy(String createdBy);
    Optional<KnowledgeBase> findByCollectionName(String collectionName);
    List<KnowledgeBase> findByDeletedFalse();
    List<KnowledgeBase> findByCreatedByAndDeletedFalse(String createdBy);
}
