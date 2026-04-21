package org.example.consultant.reasoning;

import org.example.consultant.graph.MedicalKnowledgeGraphService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class AdaptiveReasoningService {

    public ReasoningPlan plan(String message, MedicalKnowledgeGraphService.GraphContext graphContext) {
        String normalized = normalize(message);

        if (containsAny(normalized, "胸痛", "呼吸困难", "昏迷", "抽搐", "意识不清")
                || normalized.contains("高血压危象")) {
            return new ReasoningPlan(
                    ReasoningMode.EMERGENCY_ASSESSMENT,
                    "紧急风险评估",
                    List.of("先判断是否属于危险信号", "明确是否需要立即急诊", "只给简短、明确、可执行建议")
            );
        }

        if (containsAny(normalized, "药", "抗生素", "布洛芬", "对乙酰氨基酚", "副作用", "一起吃", "同吃", "剂量")) {
            return new ReasoningPlan(
                    ReasoningMode.MEDICATION_SAFETY,
                    "用药安全",
                    List.of("先判断药物是否对症", "说明能否自行使用或联用", "补充常见风险、禁忌和就医边界")
            );
        }

        if (containsAny(normalized, "报告", "体检", "指标", "化验", "检查结果", "血压", "片子")) {
            return new ReasoningPlan(
                    ReasoningMode.REPORT_INTERPRETATION,
                    "检查结果解读",
                    List.of("先解释结果代表什么", "再说明危险程度", "最后给出复查或就医建议")
            );
        }

        if (containsAny(normalized, "挂什么科", "看什么科", "去什么科", "要不要去医院", "需不需要去医院")) {
            return new ReasoningPlan(
                    ReasoningMode.DEPARTMENT_GUIDANCE,
                    "就医分诊",
                    List.of("先判断是否需要线下就医", "再给出建议科室", "补充就诊前应观察或准备的信息")
            );
        }

        if (containsAny(normalized, "发热", "发烧", "咳嗽", "头痛", "腹痛", "恶心", "怎么办", "什么原因", "为什么")) {
            return new ReasoningPlan(
                    ReasoningMode.SYMPTOM_TRIAGE,
                    "症状分诊",
                    List.of("先给可能情况的初步判断", "提示危险信号", "再给居家处理和就医建议")
            );
        }

        if (graphContext != null && graphContext.hasEvidence()) {
            return new ReasoningPlan(
                    ReasoningMode.KNOWLEDGE_GUIDED,
                    "知识图谱引导",
                    List.of("优先利用结构化证据组织回答", "避免跳结论", "把关系链解释给用户听")
            );
        }

        return new ReasoningPlan(
                ReasoningMode.GENERAL_HEALTH,
                "通用健康咨询",
                List.of("先判断用户问题属于哪类健康咨询", "不确定时明确说明信息不足", "给出保守、可执行建议")
        );
    }

    private boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public enum ReasoningMode {
        EMERGENCY_ASSESSMENT,
        MEDICATION_SAFETY,
        REPORT_INTERPRETATION,
        DEPARTMENT_GUIDANCE,
        SYMPTOM_TRIAGE,
        KNOWLEDGE_GUIDED,
        GENERAL_HEALTH
    }

    public record ReasoningPlan(ReasoningMode mode, String label, List<String> steps) {
        public String toPromptBlock() {
            StringBuilder builder = new StringBuilder("[自适应推理模式]\n");
            builder.append("- 模式: ").append(label).append(" (").append(mode.name()).append(")\n");
            builder.append("- 建议作答顺序:\n");
            int index = 1;
            for (String step : steps) {
                builder.append("  ").append(index++).append(". ").append(step).append("\n");
            }
            return builder.toString();
        }
    }
}
