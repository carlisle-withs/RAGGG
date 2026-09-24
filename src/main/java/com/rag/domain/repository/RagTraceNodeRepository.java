package com.rag.domain.repository;

import com.rag.domain.model.RagTraceNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagTraceNodeRepository extends JpaRepository<RagTraceNode, Long> {

    List<RagTraceNode> findByTraceIdAndDeletedFalseOrderByStartTimeAsc(String traceId);
}
