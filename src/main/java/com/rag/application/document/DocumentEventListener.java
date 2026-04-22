package com.rag.application.document;

import com.rag.infrastructure.mq.DocumentEventProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 监听 DocumentSavedEvent，在 Spring 事务提交后发送 Kafka 消息。
 *
 * 关键：Kafka 消息必须在事务真正提交后才发送。
 * 否则消费者可能在一个尚未提交的事务中查询 entity，
 * 触发 Hibernate 一级缓存 + 事务隔离问题，导致"文档已删除"误判。
 */
@Component
public class DocumentEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventListener.class);

    private final DocumentEventProducer eventProducer;

    public DocumentEventListener(DocumentEventProducer eventProducer) {
        this.eventProducer = eventProducer;
    }

    /**
     * 只有在事务成功提交后才发送 Kafka 消息。
     * 如果事务回滚，此监听器不会被触发。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDocumentSaved(DocumentSavedEvent event) {
        log.info("[{}] 事务已提交，发送 Kafka 消息: documentId={}, eventType={}",
                event.getDocumentEvent().getTraceId(),
                event.getDocumentEvent().getDocumentId(),
                event.getDocumentEvent().getEventType());
        eventProducer.sendUploaded(event.getDocumentEvent());
    }
}
