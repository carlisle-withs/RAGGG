package com.rag.application.chat.react.model;

import java.util.Map;

public class Action {

    private ActionType type;
    private Map<String, Object> params;
    private String reasoning;

    public Action() {}

    public Action(ActionType type, Map<String, Object> params, String reasoning) {
        this.type = type;
        this.params = params;
        this.reasoning = reasoning;
    }

    public ActionType getType() { return type; }
    public void setType(ActionType type) { this.type = type; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getCacheKey() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name());
        if (params != null && !params.isEmpty()) {
            params.forEach((k, v) -> sb.append("_").append(k).append("=").append(v));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Action{" +
                "type=" + type +
                ", params=" + params +
                ", reasoning='" + reasoning + '\'' +
                '}';
    }
}