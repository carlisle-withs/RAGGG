package com.rag.domain.repository;

import com.rag.domain.model.ConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, String> {

    Optional<ConversationSummary> findByConversationId(String conversationId);

    Optional<ConversationSummary> findByUserIdAndConversationId(String userId, String conversationId);

    void deleteByConversationId(String conversationId);
}
