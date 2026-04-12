package com.rag.application.chat.react.model;

public class LoopDetectionResult {

    private boolean isLoop;
    private String fingerprint;
    private String extractedFact;
    private int consecutiveMatchCount;

    public LoopDetectionResult() {}

    public static LoopDetectionResult notLoop(String fingerprint, String extractedFact) {
        LoopDetectionResult result = new LoopDetectionResult();
        result.isLoop = false;
        result.fingerprint = fingerprint;
        result.extractedFact = extractedFact;
        return result;
    }

    public static LoopDetectionResult loop(String fingerprint, String extractedFact, int matchCount) {
        LoopDetectionResult result = new LoopDetectionResult();
        result.isLoop = true;
        result.fingerprint = fingerprint;
        result.extractedFact = extractedFact;
        result.consecutiveMatchCount = matchCount;
        return result;
    }

    public boolean isLoop() { return isLoop; }
    public void setLoop(boolean loop) { isLoop = loop; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getExtractedFact() { return extractedFact; }
    public void setExtractedFact(String extractedFact) { this.extractedFact = extractedFact; }
    public int getConsecutiveMatchCount() { return consecutiveMatchCount; }
    public void setConsecutiveMatchCount(int consecutiveMatchCount) { this.consecutiveMatchCount = consecutiveMatchCount; }
}