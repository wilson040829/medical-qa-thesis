package org.example.consultant.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MedicalScopeGuardServiceTest {

    private final MedicalScopeGuardService service = new MedicalScopeGuardService();

    @Test
    void shouldAllowMedicalQuestion() {
        MedicalScopeGuardService.ScopeDecision decision = service.evaluate("我最近头痛恶心，要不要去医院？");

        assertTrue(decision.inScope());
        assertTrue(decision.medicalHits().contains("头痛") || decision.medicalHits().contains("恶心"));
    }

    @Test
    void shouldRejectClearlyNonMedicalQuestion() {
        MedicalScopeGuardService.ScopeDecision decision = service.evaluate("帮我写一个 Java 接口，并修复 bug");

        assertFalse(decision.inScope());
        assertNotNull(decision.refusalMessage());
        assertTrue(decision.nonMedicalHits().contains("java") || decision.nonMedicalHits().contains("bug"));
    }

    @Test
    void shouldRejectWeatherQuestion() {
        MedicalScopeGuardService.ScopeDecision decision = service.evaluate("今天天气怎么样，适合出去玩吗？");

        assertFalse(decision.inScope());
        assertTrue(decision.nonMedicalHits().contains("天气"));
    }

    @Test
    void shouldRejectGenericNonMedicalTaskEvenWithoutKeywordHit() {
        MedicalScopeGuardService.ScopeDecision decision = service.evaluate("帮我总结一下这个项目应该怎么答辩");

        assertFalse(decision.inScope());
        assertNotNull(decision.refusalMessage());
    }

    @Test
    void shouldAllowMedicationQuestion() {
        MedicalScopeGuardService.ScopeDecision decision = service.evaluate("布洛芬和对乙酰氨基酚能一起吃吗？");

        assertTrue(decision.inScope());
        assertTrue(decision.medicalHits().contains("药") || decision.medicalHits().contains("吃药") || decision.medicalHits().isEmpty());
    }

    @Test
    void shouldPreferMedicalScopeForMixedQuestion() {
        MedicalScopeGuardService.ScopeDecision decision = service.evaluate("我高血压患者，能不能长期熬夜写代码？");

        assertTrue(decision.inScope());
        assertTrue(decision.medicalHits().contains("高血压"));
    }
}
