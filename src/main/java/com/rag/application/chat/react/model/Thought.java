package com.rag.application.chat.react.model;

public class Thought {

    private String reasoning;
    private Action suggestedAction;
    private boolean isFinalAnswer;
    private String finalAnswer;

    public Thought() {}

    public Thought(String reasoning, Action suggestedAction, boolean isFinalAnswer) {
        this.reasoning = reasoning;
        this.suggestedAction = suggestedAction;
        this.isFinalAnswer = isFinalAnswer;
    }

    public static Thought finalAnswer(String reasoning, String answer) {
        Thought thought = new Thought();
        thought.reasoning = reasoning;
        thought.isFinalAnswer = true;
        thought.finalAnswer = answer;
        return thought;
    }

    public static Thought action(String reasoning, Action action) {
        return new Thought(reasoning, action, false);
    }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public Action getSuggestedAction() { return suggestedAction; }
    public void setSuggestedAction(Action suggestedAction) { this.suggestedAction = suggestedAction; }
    public boolean isFinalAnswer() { return isFinalAnswer; }
    public void setFinalAnswer(boolean finalAnswer) { this.isFinalAnswer = finalAnswer; }
    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }

    public String getCacheKey() {
        if (suggestedAction != null) {
            return suggestedAction.getCacheKey();
        }
        return "final_answer:" + (finalAnswer != null ? finalAnswer.hashCode() : 0);
    }
}