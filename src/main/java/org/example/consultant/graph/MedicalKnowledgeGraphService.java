package org.example.consultant.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MedicalKnowledgeGraphService {

    private static final Pattern BP_READING = Pattern.compile("(\\d{2,3})\\s*/\\s*(\\d{2,3})");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphData graphData;
    private final Map<String, GraphNode> nodeIndex;
    private final Map<String, GraphNode> aliasIndex;

    public MedicalKnowledgeGraphService() {
        this.graphData = loadGraph();
        this.nodeIndex = graphData.nodes().stream()
                .collect(Collectors.toMap(GraphNode::id, node -> node, (a, b) -> a, LinkedHashMap::new));
        this.aliasIndex = buildAliasIndex(graphData.nodes());
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

    private void collectFactsForNode(GraphNode node, List<GraphFact> facts, Set<String> added) {
        for (GraphEdge edge : graphData.edges()) {
            if (!Objects.equals(edge.source(), node.id())) {
                continue;
            }
            GraphNode target = nodeIndex.get(edge.target());
            if (target == null) {
                continue;
            }
            addFact(facts, added, node.name(), relationLabel(edge.relation()), target.name(), edge.evidence());

            for (GraphEdge secondHop : graphData.edges()) {
                if (!Objects.equals(secondHop.source(), target.id())) {
                    continue;
                }
                GraphNode secondTarget = nodeIndex.get(secondHop.target());
                if (secondTarget == null) {
                    continue;
                }
                addFact(facts, added, target.name(), relationLabel(secondHop.relation()), secondTarget.name(), secondHop.evidence());
            }
        }
    }

    private void addFact(List<GraphFact> facts,
                         Set<String> added,
                         String source,
                         String relation,
                         String target,
                         String evidence) {
        String key = source + "|" + relation + "|" + target;
        if (added.add(key)) {
            facts.add(new GraphFact(source, relation, target, evidence));
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
            facts.add(new GraphFact("当前血压读数", "符合", "高血压危象", "若血压 >180/120，应尽快就医评估。"));
            facts.add(new GraphFact("高血压危象", "建议就诊科室", "急诊", "高血压危象属于需要尽快处理的高风险情况。"));
        } else if (systolic >= 140 || diastolic >= 90) {
            facts.add(new GraphFact("当前血压读数", "提示", "血压升高", "达到高血压 2 级阈值时应尽快评估并复测。"));
        } else if (systolic >= 130 || diastolic >= 80) {
            facts.add(new GraphFact("当前血压读数", "提示", "血压偏高", "需要结合多次测量与医生评估判断。"));
        }
        return facts;
    }

    private GraphData loadGraph() {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("graph/medical_knowledge_graph.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("未找到 medical_knowledge_graph.json");
            }
            return MAPPER.readValue(inputStream, GraphData.class);
        } catch (Exception e) {
            throw new RuntimeException("加载医疗知识图谱失败: " + e.getMessage(), e);
        }
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
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
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

    public record GraphFact(String source, String relation, String target, String evidence) {}

    public record GraphNode(String id, String type, String name, List<String> aliases, String summary) {}

    public record GraphEdge(String source, String relation, String target, String evidence) {}

    public record GraphData(List<GraphNode> nodes, List<GraphEdge> edges) {}
}
