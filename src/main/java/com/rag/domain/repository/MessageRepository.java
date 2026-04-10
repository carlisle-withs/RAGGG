package com.rag.domain.repository;

import com.rag.domain.model.Message;
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
}
