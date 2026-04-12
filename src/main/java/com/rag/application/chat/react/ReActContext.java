package com.rag.application.chat.react;

import com.rag.application.chat.react.model.Action;
import com.rag.application.chat.react.model.ActionResult;

import java.util.ArrayList;
import java.util.List;

public class ReActContext {

    private String query;
    private String conversationHistory;
    private String summary;
    private List<ActionResult> observations;

    public ReActContext(String query, String conversationHistory, String summary) {
        this.query = query;
        this.conversationHistory = conversationHistory;
        this.summary = summary;
        this.observations = new ArrayList<>();
    }

    public void addObservation(Action action, ActionResult result) {
        observations.add(result);
    }

    public String getQuery() { return query; }
    public String getConversationHistory() { return conversationHistory; }
    public String getSummary() { return summary; }
    public List<ActionResult> getObservations() { return observations; }

    public String getFormattedHistory() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < observations.size(); i++) {
            ActionResult obs = observations.get(i);
            sb.append(String.format("[Step %d] %s: %s%n",
                    i + 1,
                    obs.getActionType().name(),
                    obs.isSuccess() ? obs.getObservation() : obs.getErrorMessage()));
        }
        return sb.toString();
    }
}