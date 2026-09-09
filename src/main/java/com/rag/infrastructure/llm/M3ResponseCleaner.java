package com.rag.infrastructure.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MiniMax M3 响应清理器
 *
 * M3 是推理模型，响应中可能包含 &lt;think&gt;...&lt;/think&gt; 标签包裹的推理过程。
 * 本工具类提供方法清理这些标签，提取纯回答内容。
 */
public final class M3ResponseCleaner {

    private static final Logger log = LoggerFactory.getLogger(M3ResponseCleaner.class);

    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

    private M3ResponseCleaner() {}

    /**
     * 清理 M3 响应中的 &lt;think&gt; 标签
     *
     * @param content 原始响应内容
     * @return 清理后的纯回答内容
     */
    public static String clean(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        try {
            // 查找 <think> 标签
            int thinkStart = content.indexOf(THINK_OPEN);
            if (thinkStart < 0) {
                return content; // 没有 think 标签，直接返回
            }

            int thinkEnd = content.indexOf(THINK_CLOSE, thinkStart);
            if (thinkEnd < 0) {
                // 有开始标签但没有结束标签（流式截断），移除 start 标签后的内容
                log.debug("M3 response has <think> without </think>, returning content before think tag");
                return content.substring(0, thinkStart).trim();
            }

            // 拼接 think 标签之前和之后的内容
            String beforeThink = content.substring(0, thinkStart);
            String afterThink = content.substring(thinkEnd + THINK_CLOSE.length());

            String cleaned = (beforeThink + afterThink).trim();

            // 如果清理后为空，可能是纯推理回答，尝试提取 think 中的结论
            if (cleaned.isEmpty()) {
                String thinkingContent = content.substring(thinkStart + THINK_OPEN.length(), thinkEnd);
                log.debug("Content empty after think removal, using post-think content");
                return afterThink.trim();
            }

            return cleaned;

        } catch (Exception e) {
            log.warn("Failed to clean M3 response: {}", e.getMessage());
            return content;
        }
    }

    /**
     * 提取 &lt;think&gt; 标签中的推理内容
     */
    public static String extractThinking(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        int thinkStart = content.indexOf(THINK_OPEN);
        if (thinkStart < 0) {
            return "";
        }

        int thinkEnd = content.indexOf(THINK_CLOSE, thinkStart);
        if (thinkEnd < 0) {
            return content.substring(thinkStart + THINK_OPEN.length());
        }

        return content.substring(thinkStart + THINK_OPEN.length(), thinkEnd);
    }

    /**
     * 检查响应是否包含推理内容
     */
    public static boolean hasThinkTag(String content) {
        return content != null && content.contains(THINK_OPEN);
    }
}
