package com.rag.domain.repository;

import com.rag.domain.model.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, String> {
    List<KnowledgeBase> findByOwner_Id(String ownerId);
}
