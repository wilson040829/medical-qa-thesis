package org.example.consultant.session;

import org.example.consultant.Aiservice.SessionTitleService;
import org.example.consultant.dto.SessionView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SessionService {

    private final AtomicInteger counter = new AtomicInteger(1);
    private final Map<String, String> sessions = new LinkedHashMap<>();
    private final SessionTitleService sessionTitleService;

    public SessionService(SessionTitleService sessionTitleService) {
        this.sessionTitleService = sessionTitleService;
        sessions.put("id1", "新会话 id1");
        counter.set(2);
    }

    public synchronized String createSession() {
        String sessionId = "id" + counter.getAndIncrement();
        sessions.put(sessionId, "新会话 " + sessionId);
        return sessionId;
    }

    public synchronized List<SessionView> listSessions() {
        List<SessionView> list = new ArrayList<>();
        sessions.forEach((id, title) -> list.add(new SessionView(id, title)));
        return list;
    }

    public synchronized boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public synchronized void ensureTitle(String sessionId, String firstMessage) {
        String current = sessions.get(sessionId);
        if (current == null || !current.startsWith("新会话")) {
            return;
        }
        String title = buildTitle(firstMessage);
        sessions.put(sessionId, title);
    }

    private String buildTitle(String message) {
        try {
            String aiTitle = sessionTitleService.summarize(message);
            if (aiTitle == null || aiTitle.isBlank()) {
                return fallbackTitle(message);
            }
            String cleaned = aiTitle.replaceAll("[\\r\\n\"'“”‘’。，、！？：；,.!?;:]", "").trim();
            if (cleaned.length() > 20) {
                cleaned = cleaned.substring(0, 20);
            }
            return cleaned.isBlank() ? fallbackTitle(message) : cleaned;
        } catch (Exception e) {
            return fallbackTitle(message);
        }
    }

    private String fallbackTitle(String message) {
        String s = message == null ? "" : message.trim();
        if (s.isEmpty()) return "医疗咨询会话";
        if (s.length() > 14) return s.substring(0, 14) + "...";
        return s;
    }
}
