package com.rag.domain.repository;

import com.rag.domain.model.RagTraceRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RagTraceRunRepository extends JpaRepository<RagTraceRun, Long> {

    Optional<RagTraceRun> findByTraceIdAndDeletedFalse(String traceId);

    @Query("""
            select r from RagTraceRun r
            where r.deleted = false
              and (:traceId is null or r.traceId like concat('%', :traceId, '%'))
              and (:conversationId is null or r.conversationId like concat('%', :conversationId, '%'))
              and (:taskId is null or r.taskId = :taskId)
              and (:status is null or r.status = :status)
            """)
    Page<RagTraceRun> search(@Param("traceId") String traceId,
                             @Param("conversationId") String conversationId,
                             @Param("taskId") String taskId,
                             @Param("status") String status,
                             Pageable pageable);
}
