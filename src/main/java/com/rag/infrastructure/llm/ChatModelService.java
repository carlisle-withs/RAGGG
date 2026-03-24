package com.rag.infrastructure.llm;

import com.rag.config.AppConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatModelService {

    private static final Logger log = LoggerFactory.getLogger(ChatModelService.class);

    private final ChatLanguageModel chatModel;

    public ChatModelService(AppConfig appConfig) {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(appConfig.getLlm().getBaseUrl())
                .apiKey(appConfig.getLlm().getApiKey())
                .modelName(appConfig.getLlm().getModel())
                .build();
    }

    public String generate(String prompt) {
        try {
            return chatModel.generate(prompt);
        } catch (Exception e) {
            log.error("Failed to generate response", e);
            throw new RuntimeException("LLM generation failed", e);
        }
    }
}
