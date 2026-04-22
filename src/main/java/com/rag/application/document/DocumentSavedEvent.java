package com.rag.application.document;

import com.rag.domain.event.DocumentEvent;
import org.springframework.context.ApplicationEvent;

/**
 * Spring 应用程序事件，在 DocumentApplicationService.upload() 中发布。
 * 配合 @TransactionalEventListener(phase = AFTER_COMMIT) 使用，
 * 确保 Kafka 消息在数据库事务提交后才发送，
 * 避免消费者读取到未提交的 entity（导致"文档已删除"误判）。
 */
public class DocumentSavedEvent extends ApplicationEvent {

    private final DocumentEvent documentEvent;

    public DocumentSavedEvent(Object source, DocumentEvent documentEvent) {
        super(source);
        this.documentEvent = documentEvent;
    }

    public DocumentEvent getDocumentEvent() {
        return documentEvent;
    }
}
