package com.rag.application.chat;

import com.rag.application.retrieval.RetrievalApplicationService;
import com.rag.infrastructure.llm.ChatModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChatApplicationService.class);

    private final ChatModelService chatModel;
    private final RetrievalApplicationService retrievalService;

    public ChatApplicationService(ChatModelService chatModel, RetrievalApplicationService retrievalService) {
        this.chatModel = chatModel;
        this.retrievalService = retrievalService;
    }

    public ChatResponse chat(String message, String kbId) {
        try {
            // Retrieve relevant documents
            List<RetrievalApplicationService.RetrievalResult> sources = retrievalService.search(message, kbId, 5);

            // Build context
            String context = buildContext(sources);

            // Build prompt
            String prompt = buildPrompt(message, context);

            // Generate response
            String response = chatModel.generate(prompt);

            return new ChatResponse(response, sources);

        } catch (Exception e) {
            log.error("Chat failed", e);
            return new ChatResponse("抱歉，发生了错误：" + e.getMessage(), List.of());
        }
    }

    private String buildContext(List<RetrievalApplicationService.RetrievalResult> sources) {
        if (sources.isEmpty()) {
            return "";
        }

        return sources.stream()
                .map(s -> "【文档】" + s.content())
                .collect(Collectors.joining("\n\n"));
    }

    private String buildPrompt(String message, String context) {
        StringBuilder prompt = new StringBuilder();

        if (!context.isEmpty()) {
            prompt.append("参考文档：\n").append(context).append("\n\n");
        }

        prompt.append("基于以上参考文档回答问题。如果参考文档中没有相关信息，请说明不知道。\n\n");
        prompt.append("问题：").append(message);

        return prompt.toString();
    }

    public record ChatResponse(String message, List<RetrievalApplicationService.RetrievalResult> sources) {}
}
