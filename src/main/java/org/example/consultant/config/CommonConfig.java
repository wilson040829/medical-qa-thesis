package org.example.consultant.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.example.consultant.Aiservice.ConsultantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class CommonConfig {
    @Autowired
    private OpenAiChatModel model;
    //记忆对象
    @Bean
    public ChatMemory chatMemory(){
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
        return memory;
    }
    //memoryid 组
    @Bean
    public ChatMemoryProvider chatMemoryProvider(){
        ChatMemoryProvider chatMemoryProvider= new ChatMemoryProvider(){
            @Override
            public ChatMemory get(Object Memoryid){
                 return MessageWindowChatMemory.builder()
                        .id(Memoryid)
                        .maxMessages(20)
                        .build();
            }
        };
        return chatMemoryProvider;
    }
    //向量库
    @Bean
    public EmbeddingStore store(){
        //加载文档
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("content");
        //构建向量库对象
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        //构建文本操作向量化的对象
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingStore(store)
                .build();
        ingestor.ingest(documents);
        return store;
    }
    //构建向量库检索对象
    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore store){
         return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .minScore(0.9) //余弦
                .maxResults(3)  //最大查找树
                .build();
    }
}


