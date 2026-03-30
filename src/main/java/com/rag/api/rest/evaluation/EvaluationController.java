package com.rag.api.rest.evaluation;

import com.rag.application.evaluation.RAGASEvaluator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAGAS 评测接口
 */
@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {

    private final RAGASEvaluator ragasEvaluator;

    public EvaluationController(RAGASEvaluator ragasEvaluator) {
        this.ragasEvaluator = ragasEvaluator;
    }

    /**
     * 单条评测
     */
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody EvaluationRequest request) {
        if (request.question() == null || request.question().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        RAGASEvaluator.EvaluationResult result = ragasEvaluator.evaluate(
                new RAGASEvaluator.EvaluationRequest(
                        request.question(),
                        request.groundTruth(),
                        request.answer(),
                        request.contexts(),
                        request.userId(),
                        request.conversationId()
                )
        );

        return ResponseEntity.ok(result);
    }

    /**
     * 批量评测
     */
    @PostMapping("/evaluate/batch")
    public ResponseEntity<?> evaluateBatch(@RequestBody List<EvaluationRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "requests cannot be empty"));
        }

        List<RAGASEvaluator.EvaluationResult> results = requests.stream()
                .map(req -> ragasEvaluator.evaluate(
                        new RAGASEvaluator.EvaluationRequest(
                                req.question(),
                                req.groundTruth(),
                                req.answer(),
                                req.contexts(),
                                req.userId(),
                                req.conversationId()
                        )
                ))
                .toList();

        // 计算平均分
        double avgFaithfulness = results.stream().mapToDouble(RAGASEvaluator.EvaluationResult::faithfulness).average().orElse(0);
        double avgAnswerRelevancy = results.stream().mapToDouble(RAGASEvaluator.EvaluationResult::answerRelevancy).average().orElse(0);
        double avgContextPrecision = results.stream().mapToDouble(RAGASEvaluator.EvaluationResult::contextPrecision).average().orElse(0);
        double avgContextRecall = results.stream().mapToDouble(RAGASEvaluator.EvaluationResult::contextRecall).average().orElse(0);
        double avgRagasScore = results.stream().mapToDouble(RAGASEvaluator.EvaluationResult::ragasScore).average().orElse(0);

        return ResponseEntity.ok(Map.of(
                "results", results,
                "count", results.size(),
                "averages", new RAGASEvaluator.EvaluationResult(
                        Math.round(avgFaithfulness * 100.0) / 100.0,
                        Math.round(avgAnswerRelevancy * 100.0) / 100.0,
                        Math.round(avgContextPrecision * 100.0) / 100.0,
                        Math.round(avgContextRecall * 100.0) / 100.0,
                        Math.round(avgRagasScore * 100.0) / 100.0
                )
        ));
    }

    /**
     * 评测请求
     */
    public record EvaluationRequest(
            String question,
            String groundTruth,
            String answer,
            List<String> contexts,
            String userId,
            String conversationId
    ) {}
}