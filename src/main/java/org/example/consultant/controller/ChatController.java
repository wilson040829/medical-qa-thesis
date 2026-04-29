package org.example.consultant.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.example.consultant.Aiservice.ConsultantService;
import org.example.consultant.dto.ChatRequest;
import org.example.consultant.dto.SessionView;
import org.example.consultant.graph.MedicalKnowledgeGraphService;
import org.example.consultant.guard.MedicalScopeGuardService;
import org.example.consultant.memory.LocalMemoryService;
import org.example.consultant.rag.DynamicKnowledgeBaseService;
import org.example.consultant.reasoning.AdaptiveReasoningService;
import org.example.consultant.session.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@Validated
public class ChatController {

    private static final String HEADER_REASONING_MODE = "X-Reasoning-Mode";
    private static final String HEADER_REASONING_CONFIDENCE = "X-Reasoning-Confidence";
    private static final String HEADER_REASONING_SECONDARY_MODE = "X-Reasoning-Secondary-Mode";
    private static final String HEADER_CITATION_COUNT = "X-Citation-Count";
    private static final String HEADER_CITATION_DOCUMENTS = "X-Citation-Documents";
    private static final String HEADER_GRAPH_FACT_COUNT = "X-Graph-Fact-Count";

    private final ConsultantService consultantService;
    private final SessionService sessionService;
    private final LocalMemoryService localMemoryService;
    private final MedicalScopeGuardService medicalScopeGuardService;
    private final MedicalKnowledgeGraphService medicalKnowledgeGraphService;
    private final AdaptiveReasoningService adaptiveReasoningService;
    private final DynamicKnowledgeBaseService knowledgeBaseService;

    public ChatController(ConsultantService consultantService,
                          SessionService sessionService,
                          LocalMemoryService localMemoryService,
                          MedicalScopeGuardService medicalScopeGuardService,
                          MedicalKnowledgeGraphService medicalKnowledgeGraphService,
                          AdaptiveReasoningService adaptiveReasoningService,
                          DynamicKnowledgeBaseService knowledgeBaseService) {
        this.consultantService = consultantService;
        this.sessionService = sessionService;
        this.localMemoryService = localMemoryService;
        this.medicalScopeGuardService = medicalScopeGuardService;
        this.medicalKnowledgeGraphService = medicalKnowledgeGraphService;
        this.adaptiveReasoningService = adaptiveReasoningService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping("/sessions")
    public List<SessionView> listSessions() {
        return sessionService.listSessions();
    }

    @PostMapping("/sessions")
    public Map<String, String> createSession() {
        String id = sessionService.createSession();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("sessionId", id);
        return result;
    }

    @PostMapping("/chat")
    public ResponseEntity<String> chat(@Valid @RequestBody ChatRequest request) {
        validateSession(request.sessionId());
        sessionService.ensureTitle(request.sessionId(), request.message());

        int sourceTurn = localMemoryService.getSessionMemory(request.sessionId()).size() + 1;
        localMemoryService.append(request.sessionId(), "user", request.message());

        ChatResult result = generateAnswer(request.sessionId(), request.message(), sourceTurn);
        localMemoryService.append(request.sessionId(), "assistant", result.answer());
        return ResponseEntity.ok()
                .headers(buildHeaders(result))
                .contentType(MediaType.TEXT_PLAIN)
                .body(result.answer());
    }

    @PostMapping("/chat/trace")
    public ResponseEntity<ChatTraceResponse> chatTrace(@Valid @RequestBody ChatRequest request) {
        validateSession(request.sessionId());
        sessionService.ensureTitle(request.sessionId(), request.message());

        int sourceTurn = localMemoryService.getSessionMemory(request.sessionId()).size() + 1;
        localMemoryService.append(request.sessionId(), "user", request.message());

        ChatResult result = generateAnswer(request.sessionId(), request.message(), sourceTurn);
        localMemoryService.append(request.sessionId(), "assistant", result.answer());

        ChatTraceResponse response = new ChatTraceResponse(
                request.sessionId(),
                request.message(),
                result.answer(),
                result.reasoningPlan(),
                result.graphContext().matchedNodes(),
                result.graphContext().facts(),
                result.citations()
        );

        return ResponseEntity.ok()
                .headers(buildHeaders(result))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<ResponseBodyEmitter> chatStream(@Valid @RequestBody ChatRequest request) {
        validateSession(request.sessionId());
        sessionService.ensureTitle(request.sessionId(), request.message());

        int sourceTurn = localMemoryService.getSessionMemory(request.sessionId()).size() + 1;
        localMemoryService.append(request.sessionId(), "user", request.message());

        ChatResult result = generateAnswer(request.sessionId(), request.message(), sourceTurn);
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);

        CompletableFuture.runAsync(() -> {
            try {
                int step = 24;
                for (int i = 0; i < result.answer().length(); i += step) {
                    int end = Math.min(i + step, result.answer().length());
                    emitter.send(result.answer().substring(i, end), MediaType.TEXT_PLAIN);
                    Thread.sleep(25);
                }
                localMemoryService.append(request.sessionId(), "assistant", result.answer());
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(new RuntimeException("流式输出失败: " + e.getMessage(), e));
            }
        });

        return ResponseEntity.ok()
                .headers(buildHeaders(result))
                .contentType(MediaType.TEXT_PLAIN)
                .body(emitter);
    }

    @GetMapping("/chat")
    public ResponseEntity<String> chatLegacy(@RequestParam @NotBlank String message,
                                             @RequestParam(defaultValue = "id1") String sessionId) {
        validateSession(sessionId);
        sessionService.ensureTitle(sessionId, message);

        int sourceTurn = localMemoryService.getSessionMemory(sessionId).size() + 1;
        localMemoryService.append(sessionId, "user", message);

        ChatResult result = generateAnswer(sessionId, message, sourceTurn);
        localMemoryService.append(sessionId, "assistant", result.answer());
        return ResponseEntity.ok()
                .headers(buildHeaders(result))
                .contentType(MediaType.TEXT_PLAIN)
                .body(result.answer());
    }

    private ChatResult generateAnswer(String sessionId, String userMessage, int sourceTurn) {
        MedicalScopeGuardService.ScopeDecision decision = medicalScopeGuardService.evaluate(userMessage);
        AdaptiveReasoningService.ReasoningPlan reasoningPlan = adaptiveReasoningService.plan(userMessage);
        MedicalKnowledgeGraphService.GraphContext graphContext = medicalKnowledgeGraphService.query(userMessage);
        List<DynamicKnowledgeBaseService.RetrievedSegment> citations = knowledgeBaseService.trace(userMessage);

        if (!decision.inScope()) {
            return new ChatResult(decision.refusalMessage(), reasoningPlan, graphContext, citations);
        }

        String prompt = buildPromptWithMemory(sessionId, userMessage, reasoningPlan, graphContext);
        String answer = consultantService.chat(sessionId, prompt);
        localMemoryService.extractAndAppend(sessionId, userMessage, answer, sourceTurn);
        return new ChatResult(answer, reasoningPlan, graphContext, citations);
    }

    private String buildPromptWithMemory(String sessionId,
                                         String userMessage,
                                         AdaptiveReasoningService.ReasoningPlan reasoningPlan,
                                         MedicalKnowledgeGraphService.GraphContext graphContext) {
        List<LocalMemoryService.MemoryEntry> recalled = localMemoryService.recall(sessionId, userMessage, 5);

        StringBuilder sb = new StringBuilder();
        sb.append(reasoningPlan.toPromptBlock()).append("\n");

        if (graphContext.hasEvidence()) {
            sb.append(graphContext.toPromptBlock()).append("\n");
        }

        if (!recalled.isEmpty()) {
            sb.append("[长期记忆参考]\n");
            int idx = 1;
            for (LocalMemoryService.MemoryEntry e : recalled) {
                sb.append(idx++)
                        .append(". (")
                        .append(e.getType())
                        .append(", conf=")
                        .append(String.format("%.2f", e.getConfidence() == null ? 0.0 : e.getConfidence()))
                        .append(") ")
                        .append(e.getContent())
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("[回答要求]\n")
                .append("- 优先参考自适应推理模式组织回答，但不要把它机械照搬给用户。\n")
                .append("- 若知识图谱证据不足，要明确说明只是初步判断。\n")
                .append("- 若 RAG 检索到外部知识，与知识图谱冲突时，优先采用更具体、更安全、更新的医疗建议。\n")
                .append("- 若推理置信度较低，可先补问 2-3 个关键问题，再给初步建议。\n\n");

        sb.append("[用户当前问题]\n").append(userMessage);
        return sb.toString();
    }

    private HttpHeaders buildHeaders(ChatResult result) {
        AdaptiveReasoningService.ReasoningPlan plan = result.reasoningPlan();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HEADER_REASONING_MODE, plan.mode().name());
        headers.add(HEADER_REASONING_CONFIDENCE, String.format("%.2f", plan.confidence()));
        if (plan.secondaryMode() != null) {
            headers.add(HEADER_REASONING_SECONDARY_MODE, plan.secondaryMode().name());
        }
        headers.add(HEADER_CITATION_COUNT, String.valueOf(result.citations().size()));
        headers.add(HEADER_CITATION_DOCUMENTS, encodeCitationDocuments(result.citations()));
        headers.add(HEADER_GRAPH_FACT_COUNT, String.valueOf(result.graphContext().facts().size()));
        return headers;
    }

    private String encodeCitationDocuments(List<DynamicKnowledgeBaseService.RetrievedSegment> citations) {
        String joined = citations.stream()
                .map(DynamicKnowledgeBaseService.RetrievedSegment::documentName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(Collectors.joining("、"));
        String text = joined.isBlank() ? "未命中" : joined;
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private void validateSession(String sessionId) {
        if (!sessionService.exists(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "无效会话ID，请先从会话列表选择或新建会话");
        }
    }

    private record ChatResult(String answer,
                              AdaptiveReasoningService.ReasoningPlan reasoningPlan,
                              MedicalKnowledgeGraphService.GraphContext graphContext,
                              List<DynamicKnowledgeBaseService.RetrievedSegment> citations) {
    }

    public record ChatTraceResponse(String sessionId,
                                    String message,
                                    String answer,
                                    AdaptiveReasoningService.ReasoningPlan reasoning,
                                    List<String> matchedGraphNodes,
                                    List<MedicalKnowledgeGraphService.GraphFact> graphFacts,
                                    List<DynamicKnowledgeBaseService.RetrievedSegment> citations) {
    }
}
