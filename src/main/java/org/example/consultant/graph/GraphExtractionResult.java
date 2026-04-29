package org.example.consultant.graph;

import java.util.List;

public record GraphExtractionResult(List<ExtractedNode> nodes, List<ExtractedEdge> edges) {

    public GraphExtractionResult {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static GraphExtractionResult empty() {
        return new GraphExtractionResult(List.of(), List.of());
    }

    public GraphExtractionResult merge(GraphExtractionResult other) {
        if (other == null) {
            return this;
        }
        List<ExtractedNode> mergedNodes = new java.util.ArrayList<>(nodes);
        mergedNodes.addAll(other.nodes());
        List<ExtractedEdge> mergedEdges = new java.util.ArrayList<>(edges);
        mergedEdges.addAll(other.edges());
        return new GraphExtractionResult(mergedNodes, mergedEdges);
    }

    public record ExtractedNode(String name,
                                String type,
                                List<String> aliases,
                                String summary) {
    }

    public record ExtractedEdge(String source,
                                String relation,
                                String target,
                                String evidence,
                                String sourceDocumentId,
                                String sourceDocumentName,
                                String sourcePath,
                                Integer sourceSegmentIndex) {
    }
}
