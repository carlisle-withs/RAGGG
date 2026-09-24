package com.rag.domain.repository;

import com.rag.domain.model.QueryTermMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QueryTermMappingRepository extends JpaRepository<QueryTermMapping, Long> {

    List<QueryTermMapping> findByEnabledTrueAndDeletedFalse();

    @Query("""
            select m from QueryTermMapping m
            where m.deleted = false
              and (:keyword is null
                   or m.sourceTerm like concat('%', :keyword, '%')
                   or m.targetTerm like concat('%', :keyword, '%'))
            """)
    Page<QueryTermMapping> search(@Param("keyword") String keyword, Pageable pageable);
}
