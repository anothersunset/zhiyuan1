package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 意图词表注册表契约：外置 JSON 必须覆盖代码引用的全部组名，
 * 规模不得因误编辑而塌缩（否则本地路由静默退化为全走 LLM）。
 */
@DisplayName("Agent 意图词表契约")
class AgentIntentLexiconTest {

    private final AgentIntentLexicon lexicon = new AgentIntentLexicon(new ObjectMapper());

    /** 代码引用的全部组名 → 最小词条数（新增组时在此登记，删除组时同步 AgentDecisionService）。 */
    private static final java.util.Map<String, Integer> REQUIRED_GROUPS = java.util.Map.ofEntries(
            java.util.Map.entry("deleteConfirm", 2),
            java.util.Map.entry("deleteVerbs", 3),
            java.util.Map.entry("planNouns", 2),
            java.util.Map.entry("deletePastTense", 8),
            java.util.Map.entry("currentReference", 1),
            java.util.Map.entry("savePlanTriggers", 6),
            java.util.Map.entry("addPlanItemTriggers", 9),
            java.util.Map.entry("deleteConfirmNegations", 8),
            java.util.Map.entry("detailViewVerbs", 4),
            java.util.Map.entry("detailQueryVerbs", 2),
            java.util.Map.entry("detailNouns", 3),
            java.util.Map.entry("detailIntentExclusions", 3),
            java.util.Map.entry("schoolDetailNouns", 9),
            java.util.Map.entry("schoolPronouns", 9),
            java.util.Map.entry("pronounFollowIntents", 6),
            java.util.Map.entry("schoolContextCues", 3),
            java.util.Map.entry("schoolStripVerbs", 11),
            java.util.Map.entry("schoolNamePrefixes", 9),
            java.util.Map.entry("majorOverviewCues", 11),
            // L-20260921-08：移除"院校/学校"——具体专业名在场时该排除词误伤"第一个学校的临床医学专业怎么样"
            java.util.Map.entry("majorOverviewExclusions", 6),
            java.util.Map.entry("recommendCue", 1),
            java.util.Map.entry("majorDirectionNouns", 2),
            java.util.Map.entry("recommendSchoolsTriggers", 17),
            java.util.Map.entry("chongWenBaoCue", 1),
            java.util.Map.entry("chongWenBaoCombos", 5),
            java.util.Map.entry("profileQueries", 15),
            java.util.Map.entry("profileEditExclusions", 7),
            java.util.Map.entry("currentPlanTriggers", 16),
            java.util.Map.entry("slotUnknownReplies", 5)
    );

    @Test
    void coversEveryGroupReferencedByCode_withMinimumSizes() {
        REQUIRED_GROUPS.forEach((group, minSize) -> {
            assertTrue(lexicon.containsAny("测试占位文本不存在词条", group) == false || minSize == 0,
                    () -> "组 " + group + " 不应误命中无关文本（若命中，说明组内混入超泛词）");
            assertTrue(lexicon.group(group).size() >= minSize,
                    () -> "组 " + group + " 词条数塌缩：期望 ≥ " + minSize + "，实际 " + lexicon.group(group).size());
        });
    }

    @Test
    void majorKeywordDictionary_keepsCoreEntries() {
        assertTrue(lexicon.majorKeywords().size() >= 40, "专业关键词词典规模塌缩");
        assertTrue(lexicon.majorKeywords().contains("计算机"));
        assertTrue(lexicon.majorKeywords().contains("临床医学"));
    }

    @Test
    void containsAny_semantics() {
        assertTrue(lexicon.containsAny("把第2个志愿删掉吧", "deleteVerbs"));
        assertFalse(lexicon.containsAny("帮我推荐学校", "deleteVerbs"));
        assertFalse(lexicon.containsAny(null, "deleteVerbs"));
        assertFalse(lexicon.containsAny("", "deleteVerbs"));
    }

    @Test
    void orderSensitiveGroups_keepLeadingEntries() {
        // 顺序敏感组：首条决定清洗链的第一跳，误排序会改变行为
        assertEquals("帮我", lexicon.group("schoolStripVerbs").get(0));
        assertEquals("帮我看看", lexicon.group("schoolNamePrefixes").get(0));
        assertEquals("他的", lexicon.majorKeywordPrefixes().get(0));
        assertTrue(lexicon.majorKeywordPrefixes().size() >= 16, "修饰前缀剥离链规模塌缩");
        assertTrue(lexicon.keywordStopwords().contains("热门"), "停用词表塌缩会导致'他的热门'泄漏成关键词");
    }

    @Test
    void unknownGroup_isDefensiveNoMatch() {
        assertFalse(lexicon.containsAny("任意文本", "noSuchGroup"));
        assertTrue(lexicon.group("noSuchGroup").isEmpty());
    }
}
