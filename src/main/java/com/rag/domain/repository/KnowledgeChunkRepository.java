package com.rag.domain.repository;

import com.rag.domain.model.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    List<KnowledgeChunk> findByDocId(Long docId);
    List<KnowledgeChunk> findByKbId(Long kbId);
    List<KnowledgeChunk> findByDocIdAndDeletedFalse(Long docId);
    List<KnowledgeChunk> findByKbIdAndDeletedFalse(Long kbId);
    int countByDocId(Long docId);
}
