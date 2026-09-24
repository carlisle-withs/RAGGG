package com.rag.application.chat.react.model;

import java.util.List;

public class ActionResult {

    private ActionType actionType;
    private boolean success;
    private String observation;
    private String errorMessage;
    private long executionTimeMs;
    /** RETRIEVE_KNOWLEDGE 动作携带的检索命中（引用溯源用；不参与缓存与相等性） */
    private transient List<?> sources;

    public ActionResult() {}

    public ActionResult(ActionType actionType, boolean success, String observation) {
        this.actionType = actionType;
        this.success = success;
        this.observation = observation;
    }

    public static ActionResult failure(ActionType actionType, String errorMessage) {
        ActionResult result = new ActionResult();
        result.actionType = actionType;
        result.success = false;
        result.errorMessage = errorMessage;
        return result;
    }

    public static ActionResult success(ActionType actionType, String observation) {
        ActionResult result = new ActionResult();
        result.actionType = actionType;
        result.success = true;
        result.observation = observation;
        return result;
    }

    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getObservation() { return observation; }
    public void setObservation(String observation) { this.observation = observation; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    public List<?> getSources() { return sources; }
    public void setSources(List<?> sources) { this.sources = sources; }
}