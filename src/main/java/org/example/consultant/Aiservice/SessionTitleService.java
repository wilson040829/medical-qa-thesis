package org.example.consultant.Aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface SessionTitleService {

    @SystemMessage("你是会话标题生成助手。请根据用户首条问题生成一个简洁中文标题，用于会话列表展示。要求：1) 8-16个字；2) 不带标点和引号；3) 只输出标题本身。")
    String summarize(@UserMessage String firstQuestion);
}
