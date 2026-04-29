package org.example.consultant.reasoning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveReasoningServiceTest {

    private final AdaptiveReasoningService service = new AdaptiveReasoningService();

    @Test
    void shouldClassifyMedicationSafetyQuestion() {
        AdaptiveReasoningService.ReasoningPlan plan = service.plan("布洛芬和对乙酰氨基酚能一起吃吗？");

        assertEquals(AdaptiveReasoningService.ReasoningMode.MEDICATION_SAFETY, plan.mode());
        assertTrue(plan.confidence() >= 0.70);
    }

    @Test
    void shouldClassifyDepartmentGuidanceQuestion() {
        AdaptiveReasoningService.ReasoningPlan plan = service.plan("发烧三天要不要去医院，应该挂什么科？");

        assertEquals(AdaptiveReasoningService.ReasoningMode.DEPARTMENT_GUIDANCE, plan.mode());
    }

    @Test
    void shouldClassifyEmergencyQuestionFirst() {
        AdaptiveReasoningService.ReasoningPlan plan = service.plan("突然胸痛还呼吸困难，需要马上去医院吗？");

        assertEquals(AdaptiveReasoningService.ReasoningMode.EMERGENCY_ASSESSMENT, plan.mode());
        assertTrue(plan.confidence() >= 0.80);
    }

    @Test
    void shouldClassifyReportInterpretationQuestion() {
        AdaptiveReasoningService.ReasoningPlan plan = service.plan("体检报告显示血压 150/95，这个指标说明什么？");

        assertEquals(AdaptiveReasoningService.ReasoningMode.REPORT_INTERPRETATION, plan.mode());
    }

    @Test
    void shouldFallbackToGeneralHealthWhenSignalsAreWeak() {
        AdaptiveReasoningService.ReasoningPlan plan = service.plan("最近想调理一下作息和饮食");

        assertEquals(AdaptiveReasoningService.ReasoningMode.GENERAL_HEALTH, plan.mode());
    }
}
