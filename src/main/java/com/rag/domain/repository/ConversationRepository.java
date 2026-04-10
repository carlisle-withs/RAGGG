package com.rag.domain.repository;

import com.rag.domain.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findByConversationId(String conversationId);
    List<Conversation> findByUserId(String userId);
    List<Conversation> findByUserIdAndDeletedFalse(String userId);
    List<Conversation> findByDeletedFalseOrderByLastTimeDesc();
}
