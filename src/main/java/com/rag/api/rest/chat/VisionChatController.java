package com.rag.api.rest.chat;

import com.rag.application.chat.ChatApplicationService;
import com.rag.application.chat.ImageUnderstandingService;
import com.rag.application.retrieval.RetrievalApplicationService;
import com.rag.domain.model.User;
import com.rag.domain.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多模态聊天 Controller
 *
 * 提供 Vision QA 和多模态对话接口
 */
@RestController
@RequestMapping("/api/v1")
public class VisionChatController {

    private static final Logger log = LoggerFactory.getLogger(VisionChatController.class);

    private final ImageUnderstandingService imageService;
    private final ChatApplicationService chatService;
    private final RetrievalApplicationService retrievalService;
    private final KnowledgeBaseRepository kbRepository;

    public VisionChatController(ImageUnderstandingService imageService,
                                 ChatApplicationService chatService,
                                 RetrievalApplicationService retrievalService,
                                 KnowledgeBaseRepository kbRepository) {
        this.imageService = imageService;
        this.chatService = chatService;
        this.retrievalService = retrievalService;
        this.kbRepository = kbRepository;
    }

    /**
     * Vision QA - 上传图片提问
     *
     * POST /api/v1/chat/vision
     */
    @PostMapping("/chat/vision")
    public ResponseEntity<Map<String, Object>> visionChat(
            @RequestParam("image") MultipartFile image,
            @RequestParam("question") String question,
            @RequestParam(value = "kbId", required = false) String kbId,
            @RequestParam(value = "conversationId", required = false) String conversationId) {

        if (!imageService.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "MULTIMODAL_DISABLED",
                    "message", "多模态功能未启用，请在配置中开启 llm.multimodal.enabled=true"
            ));
        }

        try {
            // 1. 图片转 base64
            String imageBase64 = Base64.getEncoder().encodeToString(image.getBytes());
            log.info("Vision QA: image={}, size={}KB, questionLen={}",
                    image.getOriginalFilename(), image.getSize() / 1024, question.length());

            // 2. 如果指定了知识库，先检索相关文档作为上下文
            String context = "";
            if (kbId != null && !kbId.isEmpty()) {
                List<RetrievalApplicationService.RetrievalResult> results =
                        retrievalService.hybridSearch(question, kbId, 5, true);
                if (!results.isEmpty()) {
                    context = results.stream()
                            .map(r -> "【文档】" + r.content())
                            .collect(Collectors.joining("\n\n"));
                }
            }

            // 3. 调用多模态 QA
            ImageUnderstandingService.VisionQAResult result =
                    imageService.visionQA(imageBase64, question, context);

            Map<String, Object> response = new HashMap<>();
            response.put("answer", result.answer());
            response.put("imageDescription", result.imageDescription());
            response.put("references", result.references());
            response.put("conversationId", conversationId != null ? conversationId : UUID.randomUUID().toString());
            response.put("imageName", image.getOriginalFilename());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Vision QA failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "VISION_QA_FAILED",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 多模态对话 - 支持图片 + 文本 + 知识库
     *
     * POST /api/v1/chat/multimodal
     */
    @PostMapping("/chat/multimodal")
    public ResponseEntity<Map<String, Object>> multimodalChat(
            @RequestParam("message") String message,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "kbId", required = false) String kbId,
            @RequestParam(value = "conversationId", required = false) String conversationId) {

        if (!imageService.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "MULTIMODAL_DISABLED",
                    "message", "多模态功能未启用"
            ));
        }

        try {
            String conversationId_ = conversationId != null ? conversationId : UUID.randomUUID().toString();
            User currentUser = getCurrentUser();
            String userId = currentUser != null ? currentUser.getId().toString() : null;

            // 1. 如果有图片，先做图片理解
            String imageContext = "";
            if (images != null && !images.isEmpty()) {
                List<String> imageBase64List = new ArrayList<>();
                for (MultipartFile img : images) {
                    imageBase64List.add(Base64.getEncoder().encodeToString(img.getBytes()));
                }
                imageContext = imageService.generateDocumentImageDescriptions(imageBase64List);
            }

            // 2. 使用标准 Chat 服务（加入了图片上下文）
            String enrichedMessage = message;
            if (!imageContext.isEmpty()) {
                enrichedMessage = "【图片描述】\n" + imageContext + "\n\n【用户问题】\n" + message;
            }

            ChatApplicationService.ChatResponse response = chatService.chat(
                    enrichedMessage, kbId, userId, conversationId_);

            Map<String, Object> result = new HashMap<>();
            result.put("conversationId", conversationId_);
            result.put("message", response.message());
            result.put("sources", response.sources() != null ? response.sources().stream()
                    .map(s -> Map.of("chunkId", s.chunkId(), "content", s.content(), "score", s.score()))
                    .toList() : List.of());
            result.put("hasImageContext", !imageContext.isEmpty());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Multimodal chat failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "MULTIMODAL_CHAT_FAILED",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 图片分析 - 上传图片获取描述
     *
     * POST /api/v1/images/analyze
     */
    @PostMapping("/images/analyze")
    public ResponseEntity<Map<String, Object>> analyzeImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "kbId", defaultValue = "default") String kbId) {

        if (!imageService.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "MULTIMODAL_DISABLED"
            ));
        }

        try {
            String imageId = UUID.randomUUID().toString();
            ImageUnderstandingService.ImageDescription desc =
                    imageService.analyzeImage(image.getInputStream(), imageId, kbId);

            Map<String, Object> result = new HashMap<>();
            result.put("imageId", desc.imageId());
            result.put("description", desc.description());
            result.put("extractedText", desc.extractedText());
            result.put("keywords", desc.keywords());
            result.put("category", desc.category());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Image analysis failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "ANALYSIS_FAILED",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 检查多模态状态
     */
    @GetMapping("/chat/multimodal/status")
    public ResponseEntity<Map<String, Object>> multimodalStatus() {
        return ResponseEntity.ok(Map.of(
                "enabled", imageService.isAvailable(),
                "model", "MiniMax-M3",
                "features", List.of("vision", "image-analysis", "vision-qa")
        ));
    }

    // ──────────────── auth helpers ────────────────

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) return null;
        if (auth.getPrincipal() instanceof User) return (User) auth.getPrincipal();
        return null;
    }
}
