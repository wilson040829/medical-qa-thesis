package org.example.consultant.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class CommonConfig {

    private static final Logger log = LoggerFactory.getLogger(CommonConfig.class);

    @Value("${rag.splitter.max-segment-size:500}")
    private int maxSegmentSize;

    @Value("${rag.splitter.max-overlap-size:80}")
    private int maxOverlapSize;

    @Value("${rag.retriever.min-score:0.72}")
    private double minScore;

    @Value("${rag.retriever.max-results:6}")
    private int maxResults;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> store() {
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("content");

        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize))
                .embeddingStore(store)
                .build();

        long start = System.currentTimeMillis();
        ingestor.ingest(documents);
        long cost = System.currentTimeMillis() - start;

        log.info("RAG ingest finished: docs={}, splitter=recursive({}, {}), costMs={}",
                documents.size(), maxSegmentSize, maxOverlapSize, cost);

        return store;
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store) {
        log.info("RAG retriever configured: minScore={}, maxResults={}", minScore, maxResults);
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .minScore(minScore)
                .maxResults(maxResults)
                .build();
    }
}
