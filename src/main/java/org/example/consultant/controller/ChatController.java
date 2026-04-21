package org.example.consultant.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.example.consultant.Aiservice.ConsultantService;
import org.example.consultant.dto.ChatRequest;
import org.example.consultant.dto.SessionView;
import org.example.consultant.graph.MedicalKnowledgeGraphService;
import org.example.consultant.guard.MedicalScopeGuardService;
import org.example.consultant.memory.LocalMemoryService;
import org.example.consultant.reasoning.AdaptiveReasoningService;
import org.example.consultant.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@Validated
public class ChatController {

    private final ConsultantService consultantService;
    private final SessionService sessionService;
    private final LocalMemoryService localMemoryService;
    private final MedicalScopeGuardService medicalScopeGuardService;
    private final MedicalKnowledgeGraphService medicalKnowledgeGraphService;
    private final AdaptiveReasoningService adaptiveReasoningService;

    public ChatController(ConsultantService consultantService,
                          SessionService sessionService,
                          LocalMemoryService localMemoryService,
                          MedicalScopeGuardService medicalScopeGuardService,
                          MedicalKnowledgeGraphService medicalKnowledgeGraphService,
                          AdaptiveReasoningService adaptiveReasoningService) {
        this.consultantService = consultantService;
        this.sessionService = sessionService;
        this.localMemoryService = localMemoryService;
        this.medicalScopeGuardService = medicalScopeGuardService;
        this.medicalKnowledgeGraphService = medicalKnowledgeGraphService;
        this.adaptiveReasoningService = adaptiveReasoningService;
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
    public String chat(@Valid @RequestBody ChatRequest request) {
        validateSession(request.sessionId());
        sessionService.ensureTitle(request.sessionId(), request.message());

        int sourceTurn = localMemoryService.getSessionMemory(request.sessionId()).size() + 1;
        localMemoryService.append(request.sessionId(), "user", request.message());

        String answer = generateAnswer(request.sessionId(), request.message(), sourceTurn);
        localMemoryService.append(request.sessionId(), "assistant", answer);
        return answer;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseBodyEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        validateSession(request.sessionId());
        sessionService.ensureTitle(request.sessionId(), request.message());

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                int sourceTurn = localMemoryService.getSessionMemory(request.sessionId()).size() + 1;
                localMemoryService.append(request.sessionId(), "user", request.message());

                String answer = generateAnswer(request.sessionId(), request.message(), sourceTurn);
                int step = 24;
                for (int i = 0; i < answer.length(); i += step) {
                    int end = Math.min(i + step, answer.length());
                    emitter.send(answer.substring(i, end), MediaType.TEXT_PLAIN);
                    Thread.sleep(25);
                }
                localMemoryService.append(request.sessionId(), "assistant", answer);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(new RuntimeException("流式输出失败: " + e.getMessage(), e));
            }
        });
        return emitter;
    }

    // 兼容旧调用方式：GET /chat?message=...&sessionId=...
    @GetMapping("/chat")
    public String chatLegacy(@RequestParam @NotBlank String message,
                             @RequestParam(defaultValue = "id1") String sessionId) {
        validateSession(sessionId);
        sessionService.ensureTitle(sessionId, message);

        int sourceTurn = localMemoryService.getSessionMemory(sessionId).size() + 1;
        localMemoryService.append(sessionId, "user", message);

        String answer = generateAnswer(sessionId, message, sourceTurn);
        localMemoryService.append(sessionId, "assistant", answer);
        return answer;
    }

    private String generateAnswer(String sessionId, String userMessage, int sourceTurn) {
        MedicalScopeGuardService.ScopeDecision decision = medicalScopeGuardService.evaluate(userMessage);
        if (!decision.inScope()) {
            return decision.refusalMessage();
        }

        String prompt = buildPromptWithMemory(sessionId, userMessage);
        String answer = consultantService.chat(sessionId, prompt);
        localMemoryService.extractAndAppend(sessionId, userMessage, answer, sourceTurn);
        return answer;
    }

    private String buildPromptWithMemory(String sessionId, String userMessage) {
        List<LocalMemoryService.MemoryEntry> recalled = localMemoryService.recall(sessionId, userMessage, 5);
        MedicalKnowledgeGraphService.GraphContext graphContext = medicalKnowledgeGraphService.query(userMessage);
        AdaptiveReasoningService.ReasoningPlan reasoningPlan = adaptiveReasoningService.plan(userMessage, graphContext);

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
                .append("- 优先参考自适应推理模式和知识图谱证据组织回答，但不要把它们机械照搬给用户。\n")
                .append("- 若知识图谱证据不足，要明确说明只是初步判断。\n")
                .append("- 若 RAG 检索到外部知识，与知识图谱冲突时，优先采用更具体、更安全、更新的医疗建议。\n\n");

        sb.append("[用户当前问题]\n").append(userMessage);
        return sb.toString();
    }

    private void validateSession(String sessionId) {
        if (!sessionService.exists(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "无效会话ID，请先从会话列表选择或新建会话");
        }
    }
}
