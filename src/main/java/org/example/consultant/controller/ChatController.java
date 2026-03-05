package org.example.consultant.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.example.consultant.Aiservice.ConsultantService;
import org.example.consultant.dto.ChatRequest;
import org.example.consultant.dto.SessionView;
import org.example.consultant.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Validated
public class ChatController {

    private final ConsultantService consultantService;
    private final SessionService sessionService;

    public ChatController(ConsultantService consultantService, SessionService sessionService) {
        this.consultantService = consultantService;
        this.sessionService = sessionService;
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
        return consultantService.chat(request.sessionId(), request.message());
    }

    // 兼容旧调用方式：GET /chat?message=...&sessionId=...
    @GetMapping("/chat")
    public String chatLegacy(@RequestParam @NotBlank String message,
                             @RequestParam(defaultValue = "id1") String sessionId) {
        validateSession(sessionId);
        sessionService.ensureTitle(sessionId, message);
        return consultantService.chat(sessionId, message);
    }

    private void validateSession(String sessionId) {
        if (!sessionService.exists(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "无效会话ID，请先从会话列表选择或新建会话");
        }
    }
}
