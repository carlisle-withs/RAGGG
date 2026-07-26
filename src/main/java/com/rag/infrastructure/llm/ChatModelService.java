package com.rag.infrastructure.llm;

import com.rag.config.AppConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel.OpenAiChatModelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ChatModelService {

    private static final Logger log = LoggerFactory.getLogger(ChatModelService.class);

    private final ChatLanguageModel chatModel;

    public ChatModelService(AppConfig appConfig) {
        AppConfig.Llm llmConfig = appConfig.getLlm();

        OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(llmConfig.getBaseUrl() + "/chat/completions")
                .apiKey(llmConfig.getApiKey())
                .modelName(llmConfig.getModel())
                .maxTokens(4096);  // M3 reasoning model needs larger token budget

        // Add group-id header for MiniMax API
        if (llmConfig.getGroupId() != null && !llmConfig.getGroupId().isEmpty()) {
            Map<String, String> headers = new HashMap<>();
            headers.put("group-id", llmConfig.getGroupId());
            builder.customHeaders(headers);
        }

        log.info("Using LLM: provider={}, model={}, baseUrl={}, groupId={}, multimodal={}",
                llmConfig.getProvider(), llmConfig.getModel(), llmConfig.getBaseUrl(),
                llmConfig.getGroupId(), llmConfig.getMultimodal().isEnabled());

        this.chatModel = builder.build();
    }

    public String generate(String prompt) {
        try {
            String response = chatModel.generate(prompt);
            // Clean M3 <think> tags if present
            return M3ResponseCleaner.clean(response);
        } catch (Exception e) {
            log.error("Failed to generate response", e);
            throw new RuntimeException("LLM generation failed: " + e.getMessage(), e);
        }
    }
}
