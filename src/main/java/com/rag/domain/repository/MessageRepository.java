package com.rag.domain.repository;

import com.rag.domain.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByConversationIdOrderByCreateTimeAsc(String conversationId);
    List<Message> findByConversationIdAndDeletedFalseOrderByCreateTimeAsc(String conversationId);
    Optional<Message> findTopByConversationIdOrderByCreateTimeDesc(String conversationId);
    int countByConversationId(String conversationId);

    /**
     * 按主键倒序取水位线之后的最新窗口（id 自增与时间序一致，避免同秒 createTime 排序歧义）。
     * 供 MemoryService 在 Redis 窗口失效时回源重建，Pageable 控制窗口大小。
     */
    List<Message> findByConversationIdAndIdGreaterThanOrderByIdDesc(String conversationId, Long afterId, Pageable pageable);

    /** 水位线之后的应存在消息数，用于检测窗口缺口（部分失效后未重建即有新写入的场景） */
    long countByConversationIdAndIdGreaterThan(String conversationId, Long afterId);
}
