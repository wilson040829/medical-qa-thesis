package org.example.consultant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LocalMemoryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path baseDir = Paths.get("data", "memory");

    public LocalMemoryService() {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建本地记忆目录: " + baseDir.toAbsolutePath(), e);
        }
    }

    public synchronized void append(String sessionId, String role, String content) {
        MemoryEntry entry = new MemoryEntry(sessionId, role, content, Instant.now().toString());
        Path file = fileOf(sessionId);
        try {
            String json = MAPPER.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new RuntimeException("写入本地记忆失败: " + e.getMessage(), e);
        }
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

    private Path fileOf(String sessionId) {
        String safe = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return baseDir.resolve(safe + ".jsonl");
    }

    public record MemoryEntry(String sessionId, String role, String content, String timestamp) {}
}
