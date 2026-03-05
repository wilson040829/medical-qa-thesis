package org.example.consultant.Aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import jakarta.validation.constraints.NotBlank;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        chatMemory = "chatMemory", //回话记忆对象
        chatMemoryProvider = "chatMemoryProvider",  //回话记忆ID对象
        contentRetriever = "contentRetriever" //向量数据库解锁对象

)
public interface ConsultantService {
    @SystemMessage(fromResource = "system.txt")
    String chat(@MemoryId String sessionId, @UserMessage @NotBlank String message);
    //aiservice聊天方法
}
