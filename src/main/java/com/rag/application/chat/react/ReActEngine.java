package com.rag.application.chat.react;

import com.rag.application.chat.react.model.*;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private final AppConfig.React reactConfig;
    private final ReActReasoner reasoner;
    private final ActionExecutor actionExecutor;
    private final ActionCache actionCache;
    private final LoopDetector loopDetector;

    public ReActEngine(AppConfig appConfig,
                      ReActReasoner reasoner,
                      ActionExecutor actionExecutor,
                      ActionCache actionCache,
                      LoopDetector loopDetector) {
        this.reactConfig = appConfig.getReact();
        this.reasoner = reasoner;
        this.actionExecutor = actionExecutor;
        this.actionCache = actionCache;
        this.loopDetector = loopDetector;
        log.info("ReActEngine initialized: enabled={}, maxIterations={}",
                reactConfig.isEnabled(),
                reactConfig.getLoopDetection().getMaxIterations());
    }

    public ReActResult execute(String query, String kbId, ReActContext context) {
        if (!reactConfig.isEnabled()) {
            log.warn("ReActEngine is disabled");
            return ReActResult.degraded("ReAct引擎未启用", "ReAct引擎未启用", 0, new ArrayList<>());
        }

        log.info("Starting ReAct execution for query: {}", truncate(query));
        loopDetector.reset();

        List<ReActResult.ActionRecord> history = new ArrayList<>();
        int maxIterations = reactConfig.getLoopDetection().getMaxIterations();

        for (int i = 0; i < maxIterations; i++) {
            log.info("ReAct iteration {}/{}", i + 1, maxIterations);

            Thought thought = reasoner.reason(query, context);

            if (thought.isFinalAnswer()) {
                log.info("ReAct completed with final answer at iteration {}", i + 1);
                history.add(new ReActResult.ActionRecord(
                        thought.getReasoning(),
                        null,
                        ActionResult.success(ActionType.FINAL_ANSWER, thought.getFinalAnswer())
                ));
                return ReActResult.success(thought.getFinalAnswer(), i + 1, history);
            }

            Action suggestedAction = thought.getSuggestedAction();
            if (suggestedAction == null) {
                log.warn("No action suggested at iteration {}, degrading", i + 1);
                return degradeToSimpleRag(query, context, history, "未找到可执行的Action");
            }

            suggestedAction.setReasoning(thought.getReasoning());

            ActionResult cachedResult = actionCache.checkCache(suggestedAction);
            if (cachedResult != null) {
                log.info("Using cached result for action: type={}", suggestedAction.getType());
                context.addObservation(suggestedAction, cachedResult);
                history.add(new ReActResult.ActionRecord(thought.getReasoning(), suggestedAction, cachedResult));
                continue;
            }

            ActionResult result = actionExecutor.execute(suggestedAction, query, kbId);

            actionCache.getOrCompute(suggestedAction, result);

            context.addObservation(suggestedAction, result);
            history.add(new ReActResult.ActionRecord(thought.getReasoning(), suggestedAction, result));

            if (!result.isSuccess()) {
                log.warn("Action execution failed: type={}, error={}",
                        suggestedAction.getType(), result.getErrorMessage());
            }

            LoopDetectionResult loopCheck = loopDetector.detect(suggestedAction, result);

            if (loopCheck.isLoop()) {
                log.warn("Loop detected at iteration {}, degrading: fingerprint={}, matches={}",
                        i + 1, loopCheck.getFingerprint(), loopCheck.getConsecutiveMatchCount());
                return degradeToSimpleRag(query, context, history,
                        "检测到重复推理模式 (" + loopCheck.getConsecutiveMatchCount() + " 次相同)，切换到简单流程");
            }

            if (loopDetector.isMaxIterationsReached()) {
                log.warn("Max iterations reached at {}, degrading", i + 1);
                return degradeToSimpleRag(query, context, history, "达到最大迭代次数 " + maxIterations + "，切换到简单流程");
            }
        }

        log.warn("ReAct loop exited unexpectedly, degrading");
        return degradeToSimpleRag(query, context, history, "ReAct循环异常退出");
    }

    private ReActResult degradeToSimpleRag(String query, ReActContext context,
                                          List<ReActResult.ActionRecord> history, String reason) {
        log.info("Degrading to simple RAG: reason={}", reason);

        StringBuilder summary = new StringBuilder();
        summary.append("【ReAct执行摘要】\n");
        summary.append("降级原因: ").append(reason).append("\n\n");

        if (!history.isEmpty()) {
            summary.append("执行步骤:\n");
            for (int i = 0; i < history.size(); i++) {
                ReActResult.ActionRecord record = history.get(i);
                summary.append(String.format("%d. %s: %s\n",
                        i + 1,
                        record.getAction() != null ? record.getAction().getType().name() : "REASONING",
                        record.getThought()));
            }
        }

        summary.append("\n请基于以上信息，使用简单的RAG流程回答用户问题。\n");
        summary.append("用户问题: ").append(query);

        return ReActResult.degraded(summary.toString(), reason, history.size(), history);
    }

    public boolean isEnabled() {
        return reactConfig.isEnabled();
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}