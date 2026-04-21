package org.example.consultant.reasoning;

import org.example.consultant.graph.MedicalKnowledgeGraphService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveReasoningServiceTest {

    private final AdaptiveReasoningService service = new AdaptiveReasoningService();

    @Test
    void shouldClassifyMedicationSafetyQuestion() {
        AdaptiveReasoningService.ReasoningPlan plan = service.plan(
                "布洛芬和对乙酰氨基酚能一起吃吗？",
                MedicalKnowledgeGraphService.GraphContext.empty()
        );

        assertEquals(AdaptiveReasoningService.ReasoningMode.MEDICATION_SAFETY, plan.mode());
    }

    @Test
    void shouldClassifyDepartmentGuidanceQuestion() {
        AdaptiveReasoningService.ReasoningPlan plan = service.plan(
                "发烧三天要不要去医院，应该挂什么科？",
                MedicalKnowledgeGraphService.GraphContext.empty()
        );

        assertEquals(AdaptiveReasoningService.ReasoningMode.DEPARTMENT_GUIDANCE, plan.mode());
    }
}
