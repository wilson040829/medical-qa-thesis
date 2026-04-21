package org.example.consultant.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MedicalKnowledgeGraphServiceTest {

    private final MedicalKnowledgeGraphService service = new MedicalKnowledgeGraphService();

    @Test
    void shouldReturnAntibioticsGraphEvidence() {
        MedicalKnowledgeGraphService.GraphContext context = service.query("感冒和流感能不能吃抗生素？");

        assertTrue(context.hasEvidence());
        String prompt = context.toPromptBlock();
        assertTrue(prompt.contains("抗生素"));
        assertTrue(prompt.contains("不适用于"));
    }

    @Test
    void shouldDeriveHypertensiveCrisisFactFromBloodPressureReading() {
        MedicalKnowledgeGraphService.GraphContext context = service.query("我血压 185/125，要不要马上去医院？");

        assertTrue(context.hasEvidence());
        String prompt = context.toPromptBlock();
        assertTrue(prompt.contains("高血压危象"));
        assertTrue(prompt.contains("急诊"));
    }
}
