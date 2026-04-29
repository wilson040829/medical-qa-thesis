package org.example.consultant.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.data.segment.TextSegment;
import org.example.consultant.rag.DynamicKnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MedicalKnowledgeGraphService {

    private static final Pattern BP_READING = Pattern.compile("(\\d{2,3})\\s*/\\s*(\\d{2,3})");
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path DEFAULT_GRAPH_PATH = Paths.get("src", "main", "resources", "graph", "medical_knowledge_graph.json");

    private final KnowledgeGraphExtractorAiService extractorAiService;
    private final DynamicKnowledgeBaseService knowledgeBaseService;
    private final Path graphPath;

    private volatile GraphData graphData;
    private volatile Map<String, GraphNode> nodeIndex;
    private volatile Map<String, GraphNode> aliasIndex;

    @Autowired
    public MedicalKnowledgeGraphService(KnowledgeGraphExtractorAiService extractorAiService,
                                        DynamicKnowledgeBaseService knowledgeBaseService) {
        this(extractorAiService, knowledgeBaseService, DEFAULT_GRAPH_PATH);
    }

    MedicalKnowledgeGraphService() {
        this(null, null, DEFAULT_GRAPH_PATH);
    }

    MedicalKnowledgeGraphService(KnowledgeGraphExtractorAiService extractorAiService,
                                 DynamicKnowledgeBaseService knowledgeBaseService,
                                 Path graphPath) {
        this.extractorAiService = extractorAiService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.graphPath = graphPath;
        reload(loadGraph());
    }

    public GraphContext query(String message) {
        String normalized = normalize(message);
        if (normalized.isBlank()) {
            return GraphContext.empty();
        }

        LinkedHashSet<GraphNode> matchedNodes = new LinkedHashSet<>();
        aliasIndex.forEach((alias, node) -> {
            if (normalized.contains(alias)) {
                matchedNodes.add(node);
            }
        });

        List<GraphFact> facts = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        for (GraphNode node : matchedNodes) {
            collectFactsForNode(node, facts, added);
        }
        facts.addAll(deriveBloodPressureFacts(message));

        List<String> matchedLabels = matchedNodes.stream()
                .map(node -> node.name() + "(" + node.type() + ")")
                .toList();

        return new GraphContext(matchedLabels, facts.stream().limit(8).toList());
    }

    public synchronized GraphRebuildResult rebuildFromKnowledgeBase() {
        if (extractorAiService == null || knowledgeBaseService == null) {
            throw new IllegalStateException("知识图谱重建所需服务未就绪");
        }

        DynamicKnowledgeBaseService.KnowledgeBaseSnapshot snapshot = knowledgeBaseService.snapshot();
        Map<String, MutableNode> mergedNodes = new LinkedHashMap<>();
        List<GraphEdge> mergedEdges = new ArrayList<>();
        int processedSegments = 0;
        int failedSegments = 0;

        for (TextSegment segment : snapshot.segments()) {
            String text = Optional.ofNullable(segment.text()).orElse("").trim();
            if (text.isBlank()) {
                continue;
            }
            processedSegments++;
            try {
                GraphExtractionResult extracted = parseExtraction(extractorAiService.extract(buildPrompt(segment)));
                mergeExtraction(mergedNodes, mergedEdges, extracted, segment);
            } catch (Exception e) {
                failedSegments++;
            }
        }

        List<GraphNode> nodes = mergedNodes.values().stream()
                .map(MutableNode::toGraphNode)
                .toList();
        GraphData data = new GraphData(nodes, mergedEdges);
        writeGraph(data);
        reload(data);
        return new GraphRebuildResult(true,
                snapshot.documents().size(),
                processedSegments,
                nodes.size(),
                mergedEdges.size(),
                failedSegments,
                Instant.now().toString(),
                graphPath.toAbsolutePath().toString());
    }

    public GraphOverview overview() {
        GraphData current = this.graphData;
        String updatedAt = null;
        try {
            if (Files.exists(graphPath)) {
                updatedAt = Files.getLastModifiedTime(graphPath).toInstant().toString();
            }
        } catch (IOException ignore) {
        }
        return new GraphOverview(
                graphPath.toAbsolutePath().toString(),
                updatedAt,
                current.nodes().size(),
                current.edges().size()
        );
    }

    private void mergeExtraction(Map<String, MutableNode> mergedNodes,
                                 List<GraphEdge> mergedEdges,
                                 GraphExtractionResult extracted,
                                 TextSegment segment) {
        if (extracted == null) {
            return;
        }

        String sourceDocumentId = segment.metadata().getString(DynamicKnowledgeBaseService.META_DOCUMENT_ID);
        String sourceDocumentName = segment.metadata().getString(DynamicKnowledgeBaseService.META_DOCUMENT_NAME);
        String sourcePath = segment.metadata().getString(DynamicKnowledgeBaseService.META_SOURCE_PATH);
        Integer sourceSegmentIndex = segment.metadata().getInteger(DynamicKnowledgeBaseService.META_SEGMENT_INDEX);

        for (GraphExtractionResult.ExtractedNode node : extracted.nodes()) {
            registerNode(mergedNodes, node.name(), node.type(), node.aliases(), node.summary());
        }

        Set<String> addedEdges = mergedEdges.stream()
                .map(edge -> edge.source() + "|" + edge.relation() + "|" + edge.target() + "|" + Optional.ofNullable(edge.sourcePath()).orElse(""))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (GraphExtractionResult.ExtractedEdge edge : extracted.edges()) {
            String sourceId = registerNode(mergedNodes, edge.source(), null, List.of(), null);
            String targetId = registerNode(mergedNodes, edge.target(), null, List.of(), null);
            String key = sourceId + "|" + edge.relation() + "|" + targetId + "|" + Objects.toString(sourcePath, "");
            if (!addedEdges.add(key)) {
                continue;
            }
            mergedEdges.add(new GraphEdge(
                    sourceId,
                    normalizeRelation(edge.relation()),
                    targetId,
                    trimEvidence(edge.evidence()),
                    sourceDocumentId,
                    sourceDocumentName,
                    sourcePath,
                    sourceSegmentIndex
            ));
        }
    }

    private String registerNode(Map<String, MutableNode> mergedNodes,
                                String rawName,
                                String type,
                                List<String> aliases,
                                String summary) {
        String name = safeName(rawName);
        String key = normalize(name);
        MutableNode existing = mergedNodes.get(key);
        if (existing == null) {
            MutableNode created = new MutableNode(buildNodeId(name), defaultIfBlank(type, "concept"), name);
            created.aliases().add(name);
            mergedNodes.put(key, created);
            existing = created;
        }
        if (type != null && !type.isBlank() && ("concept".equalsIgnoreCase(existing.type()) || existing.type().isBlank())) {
            existing.setType(type.trim());
        }
        if (summary != null && !summary.isBlank() && (existing.summary() == null || existing.summary().isBlank())) {
            existing.setSummary(summary.trim());
        }
        if (aliases != null) {
            aliases.stream()
                    .filter(alias -> alias != null && !alias.isBlank())
                    .map(String::trim)
                    .forEach(existing.aliases()::add);
        }
        return existing.id();
    }

    private void collectFactsForNode(GraphNode node, List<GraphFact> facts, Set<String> added) {
        for (GraphEdge edge : graphData.edges()) {
            if (!Objects.equals(edge.source(), node.id())) {
                continue;
            }
            GraphNode target = nodeIndex.get(edge.target());
            if (target == null) {
                continue;
            }
            addFact(facts, added, node.name(), relationLabel(edge.relation()), target.name(), edge);

            for (GraphEdge secondHop : graphData.edges()) {
                if (!Objects.equals(secondHop.source(), target.id())) {
                    continue;
                }
                GraphNode secondTarget = nodeIndex.get(secondHop.target());
                if (secondTarget == null) {
                    continue;
                }
                addFact(facts, added, target.name(), relationLabel(secondHop.relation()), secondTarget.name(), secondHop);
            }
        }
    }

    private void addFact(List<GraphFact> facts,
                         Set<String> added,
                         String source,
                         String relation,
                         String target,
                         GraphEdge edge) {
        String key = source + "|" + relation + "|" + target + "|" + Objects.toString(edge.sourcePath(), "");
        if (added.add(key)) {
            facts.add(new GraphFact(
                    source,
                    relation,
                    target,
                    edge.evidence(),
                    edge.sourceDocumentId(),
                    edge.sourceDocumentName(),
                    edge.sourcePath(),
                    edge.sourceSegmentIndex()
            ));
        }
    }

    private List<GraphFact> deriveBloodPressureFacts(String message) {
        String raw = Optional.ofNullable(message).orElse("");
        if (!raw.contains("血压")) {
            return List.of();
        }
        Matcher matcher = BP_READING.matcher(raw);
        if (!matcher.find()) {
            return List.of();
        }

        int systolic = Integer.parseInt(matcher.group(1));
        int diastolic = Integer.parseInt(matcher.group(2));
        List<GraphFact> facts = new ArrayList<>();

        if (systolic > 180 && diastolic > 120) {
            facts.add(new GraphFact("当前血压读数", "符合", "高血压危象", "若血压 >180/120，应尽快就医评估。", null, null, null, null));
            facts.add(new GraphFact("高血压危象", "建议就诊科室", "急诊", "高血压危象属于需要尽快处理的高风险情况。", null, null, null, null));
        } else if (systolic >= 140 || diastolic >= 90) {
            facts.add(new GraphFact("当前血压读数", "提示", "血压升高", "达到高血压 2 级阈值时应尽快评估并复测。", null, null, null, null));
        } else if (systolic >= 130 || diastolic >= 80) {
            facts.add(new GraphFact("当前血压读数", "提示", "血压偏高", "需要结合多次测量与医生评估判断。", null, null, null, null));
        }
        return facts;
    }

    private GraphExtractionResult parseExtraction(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return GraphExtractionResult.empty();
        }
        String cleaned = rawJson.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim();
        try {
            return MAPPER.readValue(cleaned, GraphExtractionResult.class);
        } catch (Exception e) {
            return GraphExtractionResult.empty();
        }
    }

    private String buildPrompt(TextSegment segment) {
        return "文档名：" + Optional.ofNullable(segment.metadata().getString(DynamicKnowledgeBaseService.META_DOCUMENT_NAME)).orElse("未知文档") + "\n"
                + "文档路径：" + Optional.ofNullable(segment.metadata().getString(DynamicKnowledgeBaseService.META_SOURCE_PATH)).orElse("未知路径") + "\n"
                + "片段序号：" + Optional.ofNullable(segment.metadata().getInteger(DynamicKnowledgeBaseService.META_SEGMENT_INDEX)).orElse(0) + "\n"
                + "文档片段：\n" + segment.text();
    }

    private void writeGraph(GraphData data) {
        try {
            Files.createDirectories(graphPath.getParent());
            MAPPER.writeValue(graphPath.toFile(), data);
        } catch (IOException e) {
            throw new RuntimeException("写入知识图谱 JSON 失败: " + graphPath.toAbsolutePath(), e);
        }
    }

    private GraphData loadGraph() {
        try {
            if (Files.exists(graphPath)) {
                return MAPPER.readValue(graphPath.toFile(), GraphData.class);
            }
            return new GraphData(List.of(), List.of());
        } catch (Exception e) {
            throw new RuntimeException("加载医疗知识图谱失败: " + e.getMessage(), e);
        }
    }

    private void reload(GraphData data) {
        this.graphData = data == null ? new GraphData(List.of(), List.of()) : data;
        this.nodeIndex = this.graphData.nodes().stream()
                .collect(Collectors.toMap(GraphNode::id, node -> node, (a, b) -> a, LinkedHashMap::new));
        this.aliasIndex = buildAliasIndex(this.graphData.nodes());
    }

    private Map<String, GraphNode> buildAliasIndex(List<GraphNode> nodes) {
        Map<String, GraphNode> map = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            map.put(normalize(node.name()), node);
            if (node.aliases() != null) {
                for (String alias : node.aliases()) {
                    map.put(normalize(alias), node);
                }
            }
        }
        return map.entrySet().stream()
                .filter(entry -> !entry.getKey().isBlank())
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private String buildNodeId(String name) {
        return "node_" + Integer.toHexString(normalize(name).hashCode());
    }

    private String normalizeRelation(String relation) {
        return defaultIfBlank(relation, "related_to").trim();
    }

    private String trimEvidence(String evidence) {
        if (evidence == null) {
            return null;
        }
        String normalized = evidence.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100) + "...";
    }

    private String relationLabel(String relation) {
        return switch (relation) {
            case "may_suggest" -> "可能提示";
            case "warning_if" -> "警惕";
            case "department" -> "建议就诊科室";
            case "requires" -> "建议检查";
            case "not_for" -> "不适用于";
            case "can_use_if" -> "可用于";
            default -> relation;
        };
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String safeName(String name) {
        String value = Optional.ofNullable(name).orElse("").trim();
        return value.isBlank() ? "未命名实体" : value;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record GraphContext(List<String> matchedNodes, List<GraphFact> facts) {

        public static GraphContext empty() {
            return new GraphContext(List.of(), List.of());
        }

        public boolean hasEvidence() {
            return !matchedNodes.isEmpty() || !facts.isEmpty();
        }

        public String toPromptBlock() {
            if (!hasEvidence()) {
                return "";
            }
            StringBuilder builder = new StringBuilder("[知识图谱证据]\n");
            if (!matchedNodes.isEmpty()) {
                builder.append("- 命中节点: ").append(String.join("、", matchedNodes)).append("\n");
            }
            if (!facts.isEmpty()) {
                builder.append("- 结构化关系:\n");
                int index = 1;
                for (GraphFact fact : facts) {
                    builder.append("  ").append(index++)
                            .append(". ")
                            .append(fact.source())
                            .append(" --")
                            .append(fact.relation())
                            .append("--> ")
                            .append(fact.target());
                    if (fact.evidence() != null && !fact.evidence().isBlank()) {
                        builder.append("（").append(fact.evidence()).append("）");
                    }
                    builder.append("\n");
                }
            }
            return builder.toString();
        }
    }

    public record GraphFact(String source,
                            String relation,
                            String target,
                            String evidence,
                            String sourceDocumentId,
                            String sourceDocumentName,
                            String sourcePath,
                            Integer sourceSegmentIndex) {
    }

    public record GraphNode(String id, String type, String name, List<String> aliases, String summary) {
    }

    public record GraphEdge(String source,
                            String relation,
                            String target,
                            String evidence,
                            String sourceDocumentId,
                            String sourceDocumentName,
                            String sourcePath,
                            Integer sourceSegmentIndex) {
    }

    public record GraphData(List<GraphNode> nodes, List<GraphEdge> edges) {
        public GraphData {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    public record GraphOverview(String graphPath, String lastUpdatedAt, int nodeCount, int edgeCount) {
    }

    public record GraphRebuildResult(boolean ok,
                                     int documentCount,
                                     int processedSegments,
                                     int nodeCount,
                                     int edgeCount,
                                     int failedSegments,
                                     String rebuiltAt,
                                     String graphPath) {
    }

    private static class MutableNode {
        private final String id;
        private String type;
        private final String name;
        private final LinkedHashSet<String> aliases = new LinkedHashSet<>();
        private String summary;

        private MutableNode(String id, String type, String name) {
            this.id = id;
            this.type = type;
            this.name = name;
        }

        public String id() {
            return id;
        }

        public String type() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public LinkedHashSet<String> aliases() {
            return aliases;
        }

        public String summary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public GraphNode toGraphNode() {
            List<String> normalizedAliases = aliases.stream()
                    .filter(alias -> alias != null && !alias.isBlank())
                    .distinct()
                    .toList();
            return new GraphNode(id, type, name, normalizedAliases, summary);
        }
    }
}
