package org.example.consultant.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.example.consultant.Aiservice.ConsultantService;
import org.example.consultant.dto.ChatRequest;
import org.example.consultant.dto.SessionView;
import org.example.consultant.memory.LocalMemoryService;
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

    public ChatController(ConsultantService consultantService,
                          SessionService sessionService,
                          LocalMemoryService localMemoryService) {
        this.consultantService = consultantService;
        this.sessionService = sessionService;
        this.localMemoryService = localMemoryService;
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

        String prompt = buildPromptWithMemory(request.sessionId(), request.message());
        String answer = consultantService.chat(request.sessionId(), prompt);

        localMemoryService.append(request.sessionId(), "assistant", answer);
        localMemoryService.extractAndAppend(request.sessionId(), request.message(), answer, sourceTurn);
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

                emitter.send("[思考] 正在检索长期记忆与知识库...\n", MediaType.TEXT_PLAIN);
                String prompt = buildPromptWithMemory(request.sessionId(), request.message());
                String answer = consultantService.chat(request.sessionId(), prompt);
                emitter.send("[思考] 已完成生成，流式输出中...\n", MediaType.TEXT_PLAIN);

                int step = 24;
                for (int i = 0; i < answer.length(); i += step) {
                    int end = Math.min(i + step, answer.length());
                    emitter.send(answer.substring(i, end), MediaType.TEXT_PLAIN);
                    Thread.sleep(25);
                }
                localMemoryService.append(request.sessionId(), "assistant", answer);
                localMemoryService.extractAndAppend(request.sessionId(), request.message(), answer, sourceTurn);
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

        String prompt = buildPromptWithMemory(sessionId, message);
        String answer = consultantService.chat(sessionId, prompt);

        localMemoryService.append(sessionId, "assistant", answer);
        localMemoryService.extractAndAppend(sessionId, message, answer, sourceTurn);
        return answer;
    }

    private String buildPromptWithMemory(String sessionId, String userMessage) {
        List<LocalMemoryService.MemoryEntry> recalled = localMemoryService.recall(sessionId, userMessage, 5);
        if (recalled.isEmpty()) return userMessage;

        StringBuilder sb = new StringBuilder();
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
        sb.append("\n[用户当前问题]\n").append(userMessage);
        return sb.toString();
    }

    private void validateSession(String sessionId) {
        if (!sessionService.exists(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "无效会话ID，请先从会话列表选择或新建会话");
        }
    }
}
