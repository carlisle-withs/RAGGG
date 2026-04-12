package com.rag.application.chat.react.model;

public enum ActionType {
    RETRIEVE_KNOWLEDGE("从知识库检索相关信息"),
    QUERY_DATABASE("从数据库查询结构化数据"),
    CHECK_CONVERSATION_HISTORY("查询对话历史"),
    FINAL_ANSWER("生成最终答案");

    private final String description;

    ActionType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}