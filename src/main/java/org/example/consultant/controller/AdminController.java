package org.example.consultant.controller;

import org.example.consultant.graph.MedicalKnowledgeGraphService;
import org.example.consultant.memory.LocalMemoryService;
import org.example.consultant.rag.DynamicKnowledgeBaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api")
public class AdminController {

    private final LocalMemoryService localMemoryService;
    private final Environment environment;
    private final DynamicKnowledgeBaseService knowledgeBaseService;
    private final MedicalKnowledgeGraphService medicalKnowledgeGraphService;

    @Value("${rag.splitter.max-segment-size:500}")
    private int maxSegmentSize;

    @Value("${rag.splitter.max-overlap-size:80}")
    private int maxOverlapSize;

    @Value("${rag.retriever.min-score:0.72}")
    private double minScore;

    @Value("${rag.retriever.max-results:6}")
    private int maxResults;

    public AdminController(LocalMemoryService localMemoryService,
                           Environment environment,
                           DynamicKnowledgeBaseService knowledgeBaseService,
                           MedicalKnowledgeGraphService medicalKnowledgeGraphService) {
        this.localMemoryService = localMemoryService;
        this.environment = environment;
        this.knowledgeBaseService = knowledgeBaseService;
        this.medicalKnowledgeGraphService = medicalKnowledgeGraphService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("time", LocalDateTime.now().toString());
        result.put("appName", environment.getProperty("spring.application.name", "consultant"));
        result.put("memoryPath", localMemoryService.baseDir());
        result.put("memorySessions", localMemoryService.listSessions().size());
        result.put("memoryTypeStats", localMemoryService.globalTypeStats());
        result.put("knowledgeBase", knowledgeBaseService.overview());
        result.put("knowledgeGraph", medicalKnowledgeGraphService.overview());

        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("splitter.maxSegmentSize", maxSegmentSize);
        rag.put("splitter.maxOverlapSize", maxOverlapSize);
        rag.put("retriever.minScore", minScore);
        rag.put("retriever.maxResults", maxResults);
        result.put("rag", rag);
        return result;
    }

    @GetMapping("/knowledge/overview")
    public Map<String, Object> knowledgeOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBase", knowledgeBaseService.overview());
        result.put("knowledgeGraph", medicalKnowledgeGraphService.overview());
        return result;
    }

    @PostMapping("/knowledge/rebuild")
    public Map<String, Object> rebuildKnowledge() {
        DynamicKnowledgeBaseService.RebuildResult kb = knowledgeBaseService.rebuild();
        MedicalKnowledgeGraphService.GraphRebuildResult graph = medicalKnowledgeGraphService.rebuildFromKnowledgeBase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("knowledgeBase", kb);
        result.put("knowledgeGraph", graph);
        return result;
    }

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadKnowledge(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少上传一个文件");
        }
        List<DynamicKnowledgeBaseService.StoredFile> storedFiles = files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .map(knowledgeBaseService::storeFile)
                .toList();

        DynamicKnowledgeBaseService.RebuildResult kb = knowledgeBaseService.rebuild();
        MedicalKnowledgeGraphService.GraphRebuildResult graph = medicalKnowledgeGraphService.rebuildFromKnowledgeBase();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("storedFiles", storedFiles);
        result.put("knowledgeBase", kb);
        result.put("knowledgeGraph", graph);
        return result;
    }

    @GetMapping("/memory/sessions")
    public List<String> memorySessions() {
        return localMemoryService.listSessions();
    }

    @GetMapping("/memory/{sessionId}")
    public List<LocalMemoryService.MemoryEntry> memoryOfSession(@PathVariable String sessionId) {
        return localMemoryService.getSessionMemory(sessionId);
    }

    @GetMapping("/memory/{sessionId}/stats")
    public Map<String, Long> memoryStats(@PathVariable String sessionId) {
        return localMemoryService.typeStatsOf(sessionId);
    }

    @GetMapping("/memory/{sessionId}/recall")
    public List<LocalMemoryService.MemoryEntry> recall(@PathVariable String sessionId,
                                                        @RequestParam(defaultValue = "") String query,
                                                        @RequestParam(defaultValue = "5") int topK) {
        return localMemoryService.recall(sessionId, query, topK);
    }

    @PostMapping("/memory/{sessionId}/distill")
    public Map<String, Object> distill(@PathVariable String sessionId) {
        return localMemoryService.distillSession(sessionId);
    }

    @DeleteMapping("/memory/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        boolean deleted = localMemoryService.clearSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("sessionId", sessionId);
        return result;
    }

    @PostMapping("/database/reset")
    public Map<String, Object> resetDatabase() {
        int cleared = localMemoryService.clearAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("clearedSessions", cleared);
        result.put("note", "已清理本地记忆存储（文件型本地数据库）");
        return result;
    }
}
