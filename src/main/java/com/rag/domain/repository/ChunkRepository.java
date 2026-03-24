package com.rag.domain.repository;

import com.rag.domain.model.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, String> {
    List<Chunk> findByDocumentId(String documentId);
}
