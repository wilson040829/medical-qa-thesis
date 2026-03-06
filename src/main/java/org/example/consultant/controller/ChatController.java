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
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@Validated
public class ChatController {

    private final ConsultantService consultantService;
    private final SessionService sessionService;
    private final LocalMemoryService localMemoryService;
    private final AtomicInteger turnCounter = new AtomicInteger(0);

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

        int turn = turnCounter.incrementAndGet();
        localMemoryService.append(request.sessionId(), "user", request.message());

        String input = buildPromptWithMemory(request.sessionId(), request.message());
        String answer = consultantService.chat(request.sessionId(), input);

        localMemoryService.append(request.sessionId(), "assistant", answer);
        localMemoryService.extractAndAppend(request.sessionId(), request.message(), answer, turn);

        // 每 6 轮自动蒸馏一次，保持长期记忆干净
        if (turn % 6 == 0) {
            localMemoryService.distillSession(request.sessionId());
        }
        return answer;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseBodyEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        validateSession(request.sessionId());
        sessionService.ensureTitle(request.sessionId(), request.message());

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        CompletableFuture.runAsync(() -> {
            int turn = turnCounter.incrementAndGet();
            try {
                localMemoryService.append(request.sessionId(), "user", request.message());
                emitter.send("[思考] 正在检索知识库与长期记忆...\n", MediaType.TEXT_PLAIN);

                String input = buildPromptWithMemory(request.sessionId(), request.message());
                String answer = consultantService.chat(request.sessionId(), input);

                emitter.send("[思考] 已完成生成，流式输出中...\n", MediaType.TEXT_PLAIN);
                int step = 24;
                for (int i = 0; i < answer.length(); i += step) {
                    int end = Math.min(i + step, answer.length());
                    emitter.send(answer.substring(i, end), MediaType.TEXT_PLAIN);
                    Thread.sleep(25);
                }

                localMemoryService.append(request.sessionId(), "assistant", answer);
                localMemoryService.extractAndAppend(request.sessionId(), request.message(), answer, turn);
                if (turn % 6 == 0) {
                    localMemoryService.distillSession(request.sessionId());
                }

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

        int turn = turnCounter.incrementAndGet();
        localMemoryService.append(sessionId, "user", message);

        String input = buildPromptWithMemory(sessionId, message);
        String answer = consultantService.chat(sessionId, input);

        localMemoryService.append(sessionId, "assistant", answer);
        localMemoryService.extractAndAppend(sessionId, message, answer, turn);
        if (turn % 6 == 0) {
            localMemoryService.distillSession(sessionId);
        }
        return answer;
    }

    private String buildPromptWithMemory(String sessionId, String userMessage) {
        List<LocalMemoryService.MemoryEntry> recalls = localMemoryService.recall(sessionId, userMessage, 4);
        if (recalls.isEmpty()) {
            return userMessage;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【长期记忆参考（仅供本轮回答使用）】\n");
        for (LocalMemoryService.MemoryEntry e : recalls) {
            sb.append("- [")
                    .append(e.getType())
                    .append("|置信度:")
                    .append(String.format("%.2f", e.getConfidence()))
                    .append("] ")
                    .append(e.getContent())
                    .append("\n");
        }
        sb.append("\n【用户当前问题】\n").append(userMessage);
        return sb.toString();
    }

    private void validateSession(String sessionId) {
        if (!sessionService.exists(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "无效会话ID，请先从会话列表选择或新建会话");
        }
    }
}
