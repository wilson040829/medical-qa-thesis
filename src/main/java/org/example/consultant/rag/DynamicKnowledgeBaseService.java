package org.example.consultant.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Service("contentRetriever")
public class DynamicKnowledgeBaseService implements ContentRetriever {

    public static final String META_DOCUMENT_ID = "sourceDocumentId";
    public static final String META_DOCUMENT_NAME = "sourceDocumentName";
    public static final String META_SOURCE_PATH = "sourcePath";
    public static final String META_SEGMENT_INDEX = "sourceSegmentIndex";

    private static final Logger log = LoggerFactory.getLogger(DynamicKnowledgeBaseService.class);

    private final int maxSegmentSize;
    private final int maxOverlapSize;
    private final double minScore;
    private final int maxResults;
    private final Path contentDir = Paths.get("src", "main", "resources", "content");

    private volatile KnowledgeBaseSnapshot snapshot = KnowledgeBaseSnapshot.empty(contentDir);
    private volatile EmbeddingStoreContentRetriever delegate;

    public DynamicKnowledgeBaseService(@Value("${rag.splitter.max-segment-size:500}") int maxSegmentSize,
                                       @Value("${rag.splitter.max-overlap-size:80}") int maxOverlapSize,
                                       @Value("${rag.retriever.min-score:0.72}") double minScore,
                                       @Value("${rag.retriever.max-results:6}") int maxResults) {
        this.maxSegmentSize = maxSegmentSize;
        this.maxOverlapSize = maxOverlapSize;
        this.minScore = minScore;
        this.maxResults = maxResults;
    }

    @PostConstruct
    public void initialize() {
        rebuild();
    }

    @Override
    public List<Content> retrieve(Query query) {
        EmbeddingStoreContentRetriever current = delegate;
        if (current == null) {
            rebuild();
            current = delegate;
        }
        return current == null ? List.of() : current.retrieve(query);
    }

    public synchronized RebuildResult rebuild() {
        try {
            Files.createDirectories(contentDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建知识库目录: " + contentDir.toAbsolutePath(), e);
        }

        long start = System.currentTimeMillis();
        List<LoadedDocument> documents = new ArrayList<>();
        List<FailedDocument> failedDocuments = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(contentDir)) {
            paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> contentDir.relativize(path).toString()))
                    .forEach(path -> loadSingleDocument(path, documents, failedDocuments));
        } catch (IOException e) {
            throw new RuntimeException("扫描知识库目录失败: " + contentDir.toAbsolutePath(), e);
        }

        List<TextSegment> segments = splitDocuments(documents);
        InMemoryEmbeddingStore<TextSegment> newStore = new InMemoryEmbeddingStore<>();
        if (!segments.isEmpty()) {
            List<Document> segmentDocuments = segments.stream()
                    .map(segment -> Document.from(segment.text(), segment.metadata()))
                    .toList();
            EmbeddingStoreIngestor.builder()
                    .documentSplitter(document -> List.of(TextSegment.from(document.text(), document.metadata())))
                    .embeddingStore(newStore)
                    .build()
                    .ingest(segmentDocuments);
        }

        this.delegate = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(newStore)
                .minScore(minScore)
                .maxResults(maxResults)
                .build();

        this.snapshot = new KnowledgeBaseSnapshot(
                contentDir.toAbsolutePath().toString(),
                Instant.now().toString(),
                List.copyOf(documents),
                List.copyOf(segments),
                List.copyOf(failedDocuments)
        );

        long costMs = System.currentTimeMillis() - start;
        log.info("Knowledge base rebuilt: docs={}, segments={}, failed={}, costMs={}",
                documents.size(), segments.size(), failedDocuments.size(), costMs);

        return new RebuildResult(true, documents.size(), segments.size(), failedDocuments, costMs, snapshot.lastRebuildAt());
    }

    public synchronized StoredFile storeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String originalName = StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "uploaded-file"));
        if (originalName.contains("..")) {
            throw new IllegalArgumentException("非法文件名: " + originalName);
        }

        try {
            Files.createDirectories(contentDir);
            Path target = contentDir.resolve(originalName).normalize();
            if (!target.startsWith(contentDir.normalize())) {
                throw new IllegalArgumentException("非法上传路径: " + originalName);
            }
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(originalName, target.toAbsolutePath().toString(), file.getSize());
        } catch (IOException e) {
            throw new RuntimeException("保存上传文件失败: " + originalName, e);
        }
    }

    public List<RetrievedSegment> trace(String queryText) {
        List<Content> contents = retrieve(Query.from(queryText));
        List<RetrievedSegment> traces = new ArrayList<>();
        for (Content content : contents) {
            TextSegment segment = content.textSegment();
            Metadata metadata = segment.metadata();
            traces.add(new RetrievedSegment(
                    metadata.getString(META_DOCUMENT_ID),
                    metadata.getString(META_DOCUMENT_NAME),
                    metadata.getString(META_SOURCE_PATH),
                    metadata.getInteger(META_SEGMENT_INDEX),
                    scoreOf(content),
                    trimSnippet(segment.text())
            ));
        }
        return traces;
    }

    public KnowledgeBaseOverview overview() {
        KnowledgeBaseSnapshot current = this.snapshot;
        List<DocumentSummary> docs = current.documents().stream()
                .map(doc -> new DocumentSummary(doc.documentId(), doc.documentName(), doc.relativePath(), doc.textLength()))
                .toList();
        return new KnowledgeBaseOverview(
                current.contentDir(),
                current.lastRebuildAt(),
                docs,
                current.segments().size(),
                current.failedDocuments()
        );
    }

    public KnowledgeBaseSnapshot snapshot() {
        return this.snapshot;
    }

    private void loadSingleDocument(Path path,
                                    List<LoadedDocument> documents,
                                    List<FailedDocument> failedDocuments) {
        try {
            Document raw = FileSystemDocumentLoader.loadDocument(path);
            String relativePath = contentDir.relativize(path).toString().replace('\\', '/');
            String documentId = buildDocumentId(relativePath);
            Metadata metadata = raw.metadata() == null ? new Metadata() : raw.metadata().copy();
            metadata.put(META_DOCUMENT_ID, documentId);
            metadata.put(META_DOCUMENT_NAME, path.getFileName().toString());
            metadata.put(META_SOURCE_PATH, relativePath);
            Document normalized = Document.from(raw.text(), metadata);
            documents.add(new LoadedDocument(
                    documentId,
                    path.getFileName().toString(),
                    relativePath,
                    path.toAbsolutePath().toString(),
                    normalized,
                    normalized.text() == null ? 0 : normalized.text().length()
            ));
        } catch (Exception e) {
            failedDocuments.add(new FailedDocument(path.getFileName().toString(), path.toAbsolutePath().toString(), e.getMessage()));
            log.warn("Skip unsupported knowledge document: path={}, reason={}", path, e.getMessage());
        }
    }

    private List<TextSegment> splitDocuments(List<LoadedDocument> documents) {
        DocumentSplitter splitter = DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize);
        List<TextSegment> segments = new ArrayList<>();
        for (LoadedDocument document : documents) {
            List<TextSegment> splitSegments = splitter.split(document.document());
            int index = 1;
            for (TextSegment splitSegment : splitSegments) {
                Metadata metadata = splitSegment.metadata() == null ? new Metadata() : splitSegment.metadata().copy();
                metadata.put(META_DOCUMENT_ID, document.documentId());
                metadata.put(META_DOCUMENT_NAME, document.documentName());
                metadata.put(META_SOURCE_PATH, document.relativePath());
                metadata.put(META_SEGMENT_INDEX, index++);
                segments.add(TextSegment.from(splitSegment.text(), metadata));
            }
        }
        return segments;
    }

    private Double scoreOf(Content content) {
        Object score = content.metadata().get(ContentMetadata.SCORE);
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private String buildDocumentId(String relativePath) {
        String normalized = relativePath.toLowerCase(Locale.ROOT).replace('\\', '/');
        return UUID.nameUUIDFromBytes(normalized.getBytes()).toString();
    }

    private String trimSnippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220) + "...";
    }

    public record StoredFile(String fileName, String absolutePath, long size) {
    }

    public record RetrievedSegment(String documentId,
                                   String documentName,
                                   String sourcePath,
                                   Integer segmentIndex,
                                   Double score,
                                   String snippet) {
    }

    public record DocumentSummary(String documentId, String documentName, String sourcePath, int textLength) {
    }

    public record FailedDocument(String fileName, String absolutePath, String reason) {
    }

    public record RebuildResult(boolean ok,
                                int documentCount,
                                int segmentCount,
                                List<FailedDocument> failedDocuments,
                                long costMs,
                                String rebuiltAt) {
    }

    public record LoadedDocument(String documentId,
                                 String documentName,
                                 String relativePath,
                                 String absolutePath,
                                 Document document,
                                 int textLength) {
    }

    public record KnowledgeBaseSnapshot(String contentDir,
                                        String lastRebuildAt,
                                        List<LoadedDocument> documents,
                                        List<TextSegment> segments,
                                        List<FailedDocument> failedDocuments) {
        static KnowledgeBaseSnapshot empty(Path contentDir) {
            return new KnowledgeBaseSnapshot(contentDir.toAbsolutePath().toString(), null, List.of(), List.of(), List.of());
        }
    }

    public record KnowledgeBaseOverview(String contentDir,
                                        String lastRebuildAt,
                                        List<DocumentSummary> documents,
                                        int segmentCount,
                                        List<FailedDocument> failedDocuments) {
    }
}
