package com.rag.domain.repository;

import com.rag.domain.model.MessageFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageFeedbackRepository extends JpaRepository<MessageFeedback, Long> {
}
