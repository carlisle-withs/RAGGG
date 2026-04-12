package com.rag.application.chat.react;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.application.chat.react.model.Action;
import com.rag.application.chat.react.model.ActionResult;
import com.rag.application.chat.react.model.LoopDetectionResult;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class LoopDetector {

    private static final Logger log = LoggerFactory.getLogger(LoopDetector.class);

    private final AppConfig.React reactConfig;
    private final LocalLlmClient localLlmClient;
    private final ObjectMapper objectMapper;

    private final List<String> fingerprintHistory = new ArrayList<>();

    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T\\s]\\d{2}:\\d{2}:\\d{2}.*|" +
            "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|" +
            "timestamp|\\b\\d{10,13}\\b|" +
            "random|uuid|id"
    );

    public LoopDetector(AppConfig appConfig, LocalLlmClient localLlmClient, ObjectMapper objectMapper) {
        this.reactConfig = appConfig.getReact();
        this.localLlmClient = localLlmClient;
        this.objectMapper = objectMapper;
        log.info("LoopDetector initialized: enabled={}, maxIterations={}, fingerprintMatchCount={}",
                reactConfig.getLoopDetection().isEnabled(),
                reactConfig.getLoopDetection().getMaxIterations(),
                reactConfig.getLoopDetection().getFingerprintMatchCount());
    }

    public LoopDetectionResult detect(Action action, ActionResult result) {
        if (!reactConfig.getLoopDetection().isEnabled()) {
            return LoopDetectionResult.notLoop(null, null);
        }

        try {
            String extractedFact = extractCoreFact(action, result);
            String fingerprint = computeFingerprint(extractedFact);

            log.debug("Loop detection: action={}, fact={}, fingerprint={}",
                    action.getType(), truncate(extractedFact), fingerprint);

            fingerprintHistory.add(fingerprint);

            int matchCount = countConsecutiveMatches(fingerprint);

            if (matchCount >= reactConfig.getLoopDetection().getFingerprintMatchCount()) {
                log.warn("Loop detected: fingerprint={}, consecutiveMatches={}",
                        fingerprint, matchCount);
                fingerprintHistory.clear();
                return LoopDetectionResult.loop(fingerprint, extractedFact, matchCount);
            }

            return LoopDetectionResult.notLoop(fingerprint, extractedFact);

        } catch (Exception e) {
            log.error("Loop detection failed: {}", e.getMessage());
            return LoopDetectionResult.notLoop(null, null);
        }
    }

    public int getCurrentIterations() {
        return fingerprintHistory.size();
    }

    public boolean isMaxIterationsReached() {
        return fingerprintHistory.size() >= reactConfig.getLoopDetection().getMaxIterations();
    }

    public void reset() {
        fingerprintHistory.clear();
        log.debug("LoopDetector reset");
    }

    private String extractCoreFact(Action action, ActionResult result) {
        String input = String.format("Action: %s, Observation: %s",
                action.getType().name(),
                result.isSuccess() ? result.getObservation() : result.getErrorMessage());

        if (!localLlmClient.isEnabled()) {
            return simpleNoiseRemoval(input);
        }

        try {
            String llmOutput = localLlmClient.extractFacts(action.getType().name(),
                    result.isSuccess() ? result.getObservation() : result.getErrorMessage());

            return parseExtractedFacts(llmOutput);

        } catch (Exception e) {
            log.warn("LLM fact extraction failed, using simple noise removal: {}", e.getMessage());
            return simpleNoiseRemoval(input);
        }
    }

    private String simpleNoiseRemoval(String input) {
        String cleaned = NOISE_PATTERN.matcher(input).replaceAll("[REMOVED]");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private String parseExtractedFacts(String llmOutput) {
        try {
            JsonNode node = objectMapper.readTree(llmOutput);
            if (node.isArray()) {
                List<String> facts = objectMapper.convertValue(node, new TypeReference<List<String>>() {});
                return String.join("|", facts);
            }
        } catch (Exception e) {
            log.warn("Failed to parse extracted facts: {}", e.getMessage());
        }
        return simpleNoiseRemoval(llmOutput);
    }

    private String computeFingerprint(String content) {
        if (content == null || content.isBlank()) {
            return "EMPTY";
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.substring(0, 16);

        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available", e);
            return String.valueOf(content.hashCode());
        }
    }

    private int countConsecutiveMatches(String fingerprint) {
        if (fingerprintHistory.isEmpty()) {
            return 0;
        }

        int count = 0;
        String lastFingerprint = null;

        for (int i = fingerprintHistory.size() - 1; i >= 0; i--) {
            String fp = fingerprintHistory.get(i);
            if (lastFingerprint == null) {
                lastFingerprint = fp;
                count = 1;
            } else if (fp.equals(lastFingerprint)) {
                count++;
            } else {
                break;
            }
        }

        return count;
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}