package com.rag.application.chat.react.model;

import java.util.List;

public class ReActResult {

    private String answer;
    private boolean degraded;
    private String degradeReason;
    private int iterations;
    private List<ActionRecord> actionHistory;

    public ReActResult() {}

    public static ReActResult success(String answer, int iterations, List<ActionRecord> history) {
        ReActResult result = new ReActResult();
        result.answer = answer;
        result.degraded = false;
        result.iterations = iterations;
        result.actionHistory = history;
        return result;
    }

    public static ReActResult degraded(String answer, String reason, int iterations, List<ActionRecord> history) {
        ReActResult result = new ReActResult();
        result.answer = answer;
        result.degraded = true;
        result.degradeReason = reason;
        result.iterations = iterations;
        result.actionHistory = history;
        return result;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public boolean isDegraded() { return degraded; }
    public void setDegraded(boolean degraded) { this.degraded = degraded; }
    public String getDegradeReason() { return degradeReason; }
    public void setDegradeReason(String degradeReason) { this.degradeReason = degradeReason; }
    public int getIterations() { return iterations; }
    public void setIterations(int iterations) { this.iterations = iterations; }
    public List<ActionRecord> getActionHistory() { return actionHistory; }
    public void setActionHistory(List<ActionRecord> actionHistory) { this.actionHistory = actionHistory; }

    public static class ActionRecord {
        private String thought;
        private Action action;
        private ActionResult result;
        private long timestamp;

        public ActionRecord() {}

        public ActionRecord(String thought, Action action, ActionResult result) {
            this.thought = thought;
            this.action = action;
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }

        public String getThought() { return thought; }
        public void setThought(String thought) { this.thought = thought; }
        public Action getAction() { return action; }
        public void setAction(Action action) { this.action = action; }
        public ActionResult getResult() { return result; }
        public void setResult(ActionResult result) { this.result = result; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}