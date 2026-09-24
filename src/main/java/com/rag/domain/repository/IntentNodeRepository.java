package com.rag.domain.repository;

import com.rag.domain.model.IntentNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface IntentNodeRepository extends JpaRepository<IntentNode, Long> {

    List<IntentNode> findByDeletedFalseOrderByLevelAscSortOrderAsc();

    List<IntentNode> findByIdInAndDeletedFalse(List<Long> ids);

    @Modifying
    @Query("update IntentNode n set n.enabled = :enabled, n.updateTime = CURRENT_TIMESTAMP "
            + "where n.id in :ids and n.deleted = false")
    int batchSetEnabled(@Param("ids") List<Long> ids, @Param("enabled") boolean enabled);

    @Modifying
    @Query("update IntentNode n set n.deleted = true, n.updateTime = CURRENT_TIMESTAMP "
            + "where n.id in :ids and n.deleted = false")
    int batchSoftDelete(@Param("ids") List<Long> ids);
}
