package org.example.consultant.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagKnowledgeBaseIntegrationTest {

    private ContentRetriever buildRetriever() {
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("content");
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(500, 80))
                .embeddingStore(store)
                .build()
                .ingest(documents);

        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .minScore(0.72)
                .maxResults(6)
                .build();
    }

    @Test
    void shouldRetrieveAuthoritativeAntibioticsDocument() {
        ContentRetriever retriever = buildRetriever();
        List<Content> results = retriever.retrieve(Query.from("Can antibiotics treat cold or flu? 感冒和流感能不能用抗生素"));

        String joined = results.stream()
                .map(content -> content.textSegment().text())
                .reduce("", (a, b) -> a + "\n---\n" + b);

        assertTrue(joined.contains("AUTH_KB_ABX_20260413"),
                () -> "未命中新加入的抗生素权威知识库文档，实际召回内容：\n" + joined);
    }

    @Test
    void shouldRetrieveAuthoritativeHypertensionDocument() {
        ContentRetriever retriever = buildRetriever();
        List<Content> results = retriever.retrieve(Query.from("What is hypertensive crisis? 高血压危象 标准 180 120"));

        String joined = results.stream()
                .map(content -> content.textSegment().text())
                .reduce("", (a, b) -> a + "\n---\n" + b);

        assertTrue(joined.contains("AUTH_KB_HTN_20260413"),
                () -> "未命中新加入的高血压权威知识库文档，实际召回内容：\n" + joined);
    }
}
