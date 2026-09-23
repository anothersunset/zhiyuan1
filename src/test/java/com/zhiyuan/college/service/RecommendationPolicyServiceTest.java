package com.zhiyuan.college.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhiyuan.college.config.RecommendationScoringProperties;
import com.zhiyuan.college.model.dto.AdmissionCutoffWithUniversity;
import com.zhiyuan.college.model.enums.StrategyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationPolicyServiceTest {

    private RecommendationPolicyService recommendationPolicyService;

    @BeforeEach
    void setUp() {
        RecommendationScoringProperties properties = new RecommendationScoringProperties();
        recommendationPolicyService = new RecommendationPolicyService(properties);
    }

    @Test
    void evaluate_shouldMapModerateRankAdvantageToSafe() {
        AdmissionCutoffWithUniversity cutoff = buildCutoff(612, 28000);

        RecommendationPolicyService.RecommendationDecision decision =
                recommendationPolicyService.evaluate(620, 26000, cutoff);

        assertNotNull(decision);
        assertEquals("RANK", decision.recommendationBasis());
        assertEquals(StrategyType.SAFE, decision.strategy());
        assertEquals(57, decision.admissionProbability());
        assertEquals(2000, decision.rankGap());
        assertEquals(8, decision.scoreGap());
    }

    @Test
    void evaluate_shouldMapStrongScoreAdvantageToGuarantee() {
        AdmissionCutoffWithUniversity cutoff = buildCutoff(590, null);

        RecommendationPolicyService.RecommendationDecision decision =
                recommendationPolicyService.evaluate(620, null, cutoff);

        assertNotNull(decision);
        assertEquals("SCORE", decision.recommendationBasis());
        assertEquals(StrategyType.GUARANTEE, decision.strategy());
        assertEquals(85, decision.admissionProbability());
        assertEquals(30, decision.scoreGap());
    }

    @Test
    void evaluate_shouldFilterOutClearlyUnsafeCandidates() {
        AdmissionCutoffWithUniversity cutoff = buildCutoff(650, 10000);

        RecommendationPolicyService.RecommendationDecision decision =
                recommendationPolicyService.evaluate(620, 20000, cutoff);

        assertNull(decision);
    }

    private AdmissionCutoffWithUniversity buildCutoff(Integer cutoffScore, Integer minRank) {
        AdmissionCutoffWithUniversity cutoff = new AdmissionCutoffWithUniversity();
        cutoff.setCutoffScore(cutoffScore);
        cutoff.setMinRank(minRank);
        cutoff.setUniversityName("Test University");
        return cutoff;
    }

    // --- L-20260921 消融实验 A0→A1：位次一票否决 ---

    @Test
    void rankImpossible_vetoesLuckyScoreCutoff() {
        // 位次差 -16314（模型区间外），但某年分数线碰巧偏低使 score 概率进入 GUARANTEE：
        // 修复前 score 单 basis 会救回该学校（消融 A0 中 264 条此类污染），修复后位次否决。
        AdmissionCutoffWithUniversity cutoff = buildCutoff(560, 8890);

        RecommendationPolicyService.RecommendationDecision decision =
                recommendationPolicyService.evaluate(588, 25204, cutoff);

        assertNull(decision);
    }

    @Test
    void rankImpossible_explainsAsZeroProbability() {
        RecommendationPolicyService.ProbabilityBreakdown breakdown =
                recommendationPolicyService.explain(588, 25204, 560, 8890);

        assertEquals(0, breakdown.probability());
        assertNull(breakdown.strategy());
        assertFalse(breakdown.recommended());
    }

    @Test
    void missingRankData_stillFallsBackToScoreBasis() {
        // 位次数据缺失（rankGap==null）不触发否决：单分数口径保留
        RecommendationPolicyService.ProbabilityBreakdown breakdown =
                recommendationPolicyService.explain(620, null, 590, null);

        assertNotNull(breakdown.probability());
        assertTrue(breakdown.probability() >= 75);
        assertTrue(breakdown.recommended());
    }
}
