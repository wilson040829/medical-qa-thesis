package org.example.consultant.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class LocalMemoryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MEMORY_CONTENT = 220;
    private final Path baseDir = Paths.get("data", "memory");

    public LocalMemoryService() {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建本地记忆目录: " + baseDir.toAbsolutePath(), e);
        }
    }

    /**
     * 兼容旧逻辑：普通对话日志。
     */
    public synchronized void append(String sessionId, String role, String content) {
        appendStructured(sessionId, role, MemoryType.DIALOGUE.name().toLowerCase(), content, 0.60, -1);
    }

    public synchronized void appendStructured(String sessionId,
                                              String role,
                                              String type,
                                              String content,
                                              double confidence,
                                              int sourceTurn) {
        MemoryEntry entry = new MemoryEntry(
                UUID.randomUUID().toString(),
                sessionId,
                role,
                type,
                trimContent(content),
                Math.max(0.0, Math.min(1.0, confidence)),
                Instant.now().toString(),
                sourceTurn
        );
        writeEntry(fileOf(sessionId), entry);
    }

    /**
     * 从用户输入中提炼结构化记忆，尽量保留“长期有效”信息。
     */
    public synchronized List<MemoryEntry> extractAndAppend(String sessionId,
                                                           String userMessage,
                                                           String assistantAnswer,
                                                           int sourceTurn) {
        List<MemoryEntry> extracted = new ArrayList<>();

        String user = Optional.ofNullable(userMessage).orElse("").trim();
        if (!user.isBlank()) {
            if (containsAny(user, "希望", "想要", "尽量", "不要", "偏好", "习惯", "风格")) {
                extracted.add(buildExtracted(sessionId, "system", MemoryType.PREFERENCE, user, 0.86, sourceTurn));
            }
            if (containsAny(user, "正在", "准备", "计划", "打算", "目标", "本周", "今天", "阶段")) {
                extracted.add(buildExtracted(sessionId, "system", MemoryType.TASK, user, 0.82, sourceTurn));
            }
            if (containsAny(user, "我是", "我在", "我做", "我目前", "我现在", "校招", "毕业", "后端", "Java")) {
                extracted.add(buildExtracted(sessionId, "system", MemoryType.FACT, user, 0.80, sourceTurn));
            }
        }

        // 防止本轮没有抽取到关键记忆：降级记录一个简短摘要（避免丢失上下文）
        if (extracted.isEmpty() && !user.isBlank()) {
            extracted.add(buildExtracted(sessionId, "system", MemoryType.SUMMARY,
                    "本轮用户诉求：" + trimContent(user), 0.70, sourceTurn));
        }

        for (MemoryEntry e : deduplicate(sessionId, extracted)) {
            writeEntry(fileOf(sessionId), e);
        }
        return extracted;
    }

    public synchronized List<MemoryEntry> recall(String sessionId, String query, int topK) {
        List<MemoryEntry> all = getSessionMemory(sessionId);
        if (all.isEmpty()) return List.of();

        String q = Optional.ofNullable(query).orElse("");
        List<String> tokens = tokenize(q);

        return all.stream()
                .filter(e -> !MemoryType.DIALOGUE.name().equalsIgnoreCase(e.getType()))
                .map(e -> Map.entry(e, score(e, tokens)))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, topK))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 记忆蒸馏：把事实/偏好/任务汇总成一条 summary 记忆。
     */
    public synchronized Map<String, Object> distillSession(String sessionId) {
        List<MemoryEntry> all = getSessionMemory(sessionId);
        List<MemoryEntry> core = all.stream()
                .filter(e -> !MemoryType.DIALOGUE.name().equalsIgnoreCase(e.getType()))
                .collect(Collectors.toList());

        if (core.isEmpty()) {
            return Map.of("sessionId", sessionId, "distilled", false, "reason", "暂无可蒸馏记忆");
        }

        StringBuilder sb = new StringBuilder("【记忆蒸馏摘要】\n");
        appendTopByType(sb, core, MemoryType.FACT, "事实");
        appendTopByType(sb, core, MemoryType.PREFERENCE, "偏好");
        appendTopByType(sb, core, MemoryType.TASK, "任务");

        appendStructured(sessionId, "system", MemoryType.SUMMARY.name().toLowerCase(), sb.toString(), 0.93, estimateTurn(core));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("distilled", true);
        result.put("sourceCount", core.size());
        result.put("typeStats", typeStatsOf(sessionId));
        return result;
    }

    public synchronized Map<String, Long> typeStatsOf(String sessionId) {
        return getSessionMemory(sessionId).stream()
                .collect(Collectors.groupingBy(e -> normalizeType(e.getType()), LinkedHashMap::new, Collectors.counting()));
    }

    public synchronized Map<String, Long> globalTypeStats() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (String s : listSessions()) {
            typeStatsOf(s).forEach((k, v) -> map.merge(k, v, Long::sum));
        }
        return map;
    }

    public synchronized List<String> listSessions() {
        try {
            if (!Files.exists(baseDir)) return List.of();
            return Files.list(baseDir)
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jsonl"))
                    .map(n -> n.substring(0, n.length() - 6))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    public synchronized List<MemoryEntry> getSessionMemory(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.exists(file)) return List.of();
        try {
            List<MemoryEntry> list = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                list.add(MAPPER.readValue(line, MemoryEntry.class));
            }
            return list;
        } catch (Exception e) {
            throw new RuntimeException("读取本地记忆失败: " + e.getMessage(), e);
        }
    }

    public synchronized boolean clearSession(String sessionId) {
        Path file = fileOf(sessionId);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("清理记忆失败: " + e.getMessage(), e);
        }
    }

    public synchronized int clearAll() {
        int count = 0;
        for (String s : listSessions()) {
            if (clearSession(s)) count++;
        }
        return count;
    }

    public String baseDir() {
        return baseDir.toAbsolutePath().toString();
    }

    private void appendTopByType(StringBuilder sb, List<MemoryEntry> list, MemoryType type, String label) {
        List<MemoryEntry> top = list.stream()
                .filter(e -> type.name().equalsIgnoreCase(e.getType()))
                .sorted(Comparator.comparingDouble(MemoryEntry::getConfidence).reversed())
                .limit(2)
                .toList();
        if (top.isEmpty()) return;

        sb.append(label).append("：\n");
        for (MemoryEntry e : top) {
            sb.append("- ").append(e.getContent()).append("\n");
        }
    }

    private int estimateTurn(List<MemoryEntry> entries) {
        return entries.stream()
                .map(MemoryEntry::getSourceTurn)
                .filter(Objects::nonNull)
                .filter(i -> i >= 0)
                .max(Integer::compareTo)
                .orElse(-1);
    }

    private List<MemoryEntry> deduplicate(String sessionId, List<MemoryEntry> candidates) {
        List<MemoryEntry> existing = getSessionMemory(sessionId);
        Set<String> keySet = existing.stream()
                .map(e -> normalizeType(e.getType()) + "|" + e.getContent())
                .collect(Collectors.toSet());
        return candidates.stream()
                .filter(e -> keySet.add(normalizeType(e.getType()) + "|" + e.getContent()))
                .toList();
    }

    private MemoryEntry buildExtracted(String sessionId,
                                       String role,
                                       MemoryType type,
                                       String content,
                                       double confidence,
                                       int sourceTurn) {
        return new MemoryEntry(
                UUID.randomUUID().toString(),
                sessionId,
                role,
                type.name().toLowerCase(),
                trimContent(content),
                confidence,
                Instant.now().toString(),
                sourceTurn
        );
    }

    private void writeEntry(Path file, MemoryEntry entry) {
        try {
            String json = MAPPER.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new RuntimeException("写入本地记忆失败: " + e.getMessage(), e);
        }
    }

    private double score(MemoryEntry e, List<String> tokens) {
        double keyword = keywordScore(e.getContent(), tokens); // 0~1
        double confidence = Optional.ofNullable(e.getConfidence()).orElse(0.6);
        double recency = recencyScore(e.getTimestamp());
        double typeWeight = switch (normalizeType(e.getType())) {
            case "preference" -> 0.18;
            case "task" -> 0.14;
            case "fact" -> 0.12;
            case "summary" -> 0.10;
            default -> 0.05;
        };
        return keyword * 0.50 + confidence * 0.22 + recency * 0.18 + typeWeight;
    }

    private double recencyScore(String ts) {
        try {
            Instant t = Instant.parse(ts);
            long hours = Math.max(0, Duration.between(t, Instant.now()).toHours());
            if (hours <= 12) return 1.0;
            if (hours <= 48) return 0.8;
            if (hours <= 7 * 24) return 0.6;
            return 0.35;
        } catch (Exception ignore) {
            return 0.5;
        }
    }

    private double keywordScore(String content, List<String> tokens) {
        if (tokens.isEmpty()) return 0.3;
        String text = Optional.ofNullable(content).orElse("").toLowerCase(Locale.ROOT);
        int hit = 0;
        for (String t : tokens) {
            if (t.length() < 2) continue;
            if (text.contains(t.toLowerCase(Locale.ROOT))) hit++;
        }
        return Math.min(1.0, hit / (double) Math.max(1, tokens.size()));
    }

    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) return List.of();
        return Arrays.stream(query
                        .replaceAll("[，。！？；、,.!?;:/\\n\\r\\t]", " ")
                        .trim()
                        .split("\\s+"))
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(12)
                .toList();
    }

    private boolean containsAny(String s, String... keys) {
        if (s == null || s.isBlank()) return false;
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return "unknown";
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private String trimContent(String content) {
        if (content == null) return "";
        String s = content.strip();
        if (s.length() <= MAX_MEMORY_CONTENT) return s;
        return s.substring(0, MAX_MEMORY_CONTENT) + "...";
    }

    private Path fileOf(String sessionId) {
        String safe = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return baseDir.resolve(safe + ".jsonl");
    }

    public enum MemoryType {
        DIALOGUE, FACT, PREFERENCE, TASK, SUMMARY
    }

    public static class MemoryEntry {
        private String memoryId;
        private String sessionId;
        private String role;
        private String type;
        private String content;
        private Double confidence;
        private String timestamp;
        private Integer sourceTurn;

        public MemoryEntry() {
        }

        public MemoryEntry(String memoryId, String sessionId, String role, String type,
                           String content, Double confidence, String timestamp, Integer sourceTurn) {
            this.memoryId = memoryId;
            this.sessionId = sessionId;
            this.role = role;
            this.type = type;
            this.content = content;
            this.confidence = confidence;
            this.timestamp = timestamp;
            this.sourceTurn = sourceTurn;
        }

        public String getMemoryId() {
            return memoryId;
        }

        public void setMemoryId(String memoryId) {
            this.memoryId = memoryId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Double getConfidence() {
            return confidence;
        }

        public void setConfidence(Double confidence) {
            this.confidence = confidence;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }

        public Integer getSourceTurn() {
            return sourceTurn;
        }

        public void setSourceTurn(Integer sourceTurn) {
            this.sourceTurn = sourceTurn;
        }
    }
}
