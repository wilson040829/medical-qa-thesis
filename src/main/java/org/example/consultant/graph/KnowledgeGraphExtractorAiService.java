package org.example.consultant.graph;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import jakarta.validation.constraints.NotBlank;

@AiService
public interface KnowledgeGraphExtractorAiService {

    @SystemMessage("""
            你是医疗知识图谱抽取器。
            任务：从给定医疗文档片段中抽取适合问答系统使用的实体和关系。

            只输出 JSON，不要输出 markdown、解释或额外文字。
            输出格式固定为：
            {
              "nodes": [
                {
                  "name": "实体名",
                  "type": "symptom|disease|drug|examination|department|condition|advice|population",
                  "aliases": ["别名1", "别名2"],
                  "summary": "一句简短说明"
                }
              ],
              "edges": [
                {
                  "source": "源实体名",
                  "relation": "may_suggest|warning_if|department|requires|not_for|can_use_if",
                  "target": "目标实体名",
                  "evidence": "来自原文的简短证据"
                }
              ]
            }

            规则：
            1. 只抽取原文中明确表达的事实，不要脑补。
            2. 实体名尽量用医疗场景中常见、简洁的说法。
            3. aliases 可为空数组。
            4. evidence 最多 80 个字，尽量保留关键原话。
            5. 如果某片段没有可用信息，返回 {"nodes":[],"edges":[]}。
            6. 不要输出重复实体，不要输出与医疗无关内容。
            """)
    String extract(@UserMessage @NotBlank String chunkPrompt);
}
