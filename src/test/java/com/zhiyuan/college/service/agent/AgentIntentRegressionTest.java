package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.model.entity.AgentMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 语义感知回归测试集：把"哪句话应路由到哪个工具"固化为可执行规格。
 *
 * <p>治理规则：线上每出现一次意图误判（答非所问），就在这里补一条对应语料，
 * 并修复意图层直到全表通过。该文件是意图层的唯一验收口径——修改
 * AgentDecisionService 的任何正则/分支前先跑本表，改完后全表必须仍绿。
 *
 * <p>覆盖两层：
 * <ul>
 *   <li>正向路由：模糊/同义/口语化说法应命中的工具与参数；</li>
 *   <li>负向防护：陈述句、过去时、无关问题不得误触发工具（应走 REPLY）。</li>
 * </ul>
 * LLM 兜底层（qwenEnabled=true 时）负责语料之外的自由表达，不在本表口径内。
 */
@DisplayName("Agent 意图路由回归语料")
class AgentIntentRegressionTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();
    private final AgentDecisionService service = new AgentDecisionService(
            null, new ObjectMapper(), registry, false);

    private AgentDecision decide(String phrase) {
        return service.decide(phrase, List.of(), null);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private void assertRoutes(String phrase, String expectedAction, Map<String, Object> expectedArgs) {
        AgentDecision d = decide(phrase);
        assertEquals(expectedAction, d.getAction(), () -> "语料路由错误：" + phrase);
        if (expectedArgs != null) {
            for (Map.Entry<String, Object> e : expectedArgs.entrySet()) {
                assertTrue(d.getToolArgs().containsKey(e.getKey()),
                        () -> "语料缺少参数 " + e.getKey() + "：" + phrase);
                assertEquals(String.valueOf(e.getValue()), String.valueOf(d.getToolArgs().get(e.getKey())),
                        () -> "语料参数值不符 " + e.getKey() + "：" + phrase);
            }
        }
    }

    private void assertReplies(String phrase, String contentPart) {
        AgentDecision d = decide(phrase);
        assertEquals(AgentToolNames.REPLY, d.getAction(), () -> "语料路由错误：" + phrase);
        assertTrue(d.getReply() != null && d.getReply().contains(contentPart),
                () -> "兜底/引导回复缺少关键内容：" + phrase);
    }

    // ---------- getUserProfile ----------

    @ParameterizedTest(name = "[画像] {0}")
    @CsvSource({
            "看看我的画像",
            "我的画像是什么",
            "查一下我的画像信息"
    })
    void profileQueries(String phrase) {
        assertRoutes(phrase, AgentToolNames.GET_USER_PROFILE, null);
    }

    // ---------- getCurrentPlan ----------

    @ParameterizedTest(name = "[当前方案] {0}")
    @CsvSource({
            "看看我当前的志愿方案",
            "当前志愿方案是什么"
    })
    void currentPlanQueries(String phrase) {
        assertRoutes(phrase, AgentToolNames.GET_CURRENT_PLAN, null);
    }

    // ---------- recommendSchools（含 2026-09 审计新增的同义说法） ----------

    @ParameterizedTest(name = "[学校推荐] {0}")
    @CsvSource({
            "帮我推荐学校",
            "推荐志愿",
            "志愿推荐",
            "推荐大学",
            "帮我报志愿",
            "推荐一下志愿"
    })
    void schoolRecommendationQueries(String phrase) {
        assertRoutes(phrase, AgentToolNames.RECOMMEND_SCHOOLS, null);
    }

    // ---------- recommendMajors ----------

    @Test
    void majorRecommendation_withKeyword() {
        assertRoutes("帮我推荐计算机专业", AgentToolNames.RECOMMEND_MAJORS, args("majorKeyword", "计算机"));
        assertRoutes("推荐一下法学方向", AgentToolNames.RECOMMEND_MAJORS, args("majorKeyword", "法学"));
        assertRoutes("推荐好的计算机专业", AgentToolNames.RECOMMEND_MAJORS, args("majorKeyword", "计算机"));
    }

    @Test
    void majorRecommendation_fillerPhrase_asksForDirectionInsteadOfSearching() {
        // 2026-09 审计：'适合我的'曾被当成专业关键词查库，导致"暂无结果"式答非所问
        AgentDecision d = decide("推荐适合我的专业");
        assertEquals(AgentToolNames.REPLY, d.getAction());
        assertTrue(d.getReply().contains("方向"));
    }

    // ---------- getMajorOverview ----------

    @ParameterizedTest(name = "[专业概览] {0}")
    @CsvSource({
            "计算机专业主要学什么,计算机",
            "软件工程就业前景怎么样,软件工程",
            "介绍一下临床医学专业,临床医学"
    })
    void majorOverviewQueries(String phrase, String keyword) {
        assertRoutes(phrase, AgentToolNames.GET_MAJOR_OVERVIEW, args("majorKeyword", keyword));
    }

    // ---------- getSchoolDetail（序号引用，量词含"项"，位数不限） ----------

    @Test
    void schoolDetail_byOrdinal() {
        assertRoutes("看看第 2 所学校的详情", AgentToolNames.GET_SCHOOL_DETAIL, args("selectionIndex", 2));
        assertRoutes("第一个学校详情", AgentToolNames.GET_SCHOOL_DETAIL, args("selectionIndex", 1));
    }

    // ---------- getSchoolDetailByName ----------

    @Test
    void schoolDetailByName_queries() {
        assertRoutes("帮我看看湘潭大学的详情", AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME,
                args("universityName", "湘潭大学"));
        assertRoutes("查看浙江大学详情", AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME,
                args("universityName", "浙江大学"));
    }

    // ---------- addPlanItem ----------

    @Test
    void addPlanItem_byOrdinal() {
        assertRoutes("把第 1 所加入志愿单", AgentToolNames.ADD_PLAN_ITEM, args("selectionIndex", 1));
        assertRoutes("把第一个加入当前方案", AgentToolNames.ADD_PLAN_ITEM, args("selectionIndex", 1));
        assertRoutes("把第 45 所加入志愿单", AgentToolNames.ADD_PLAN_ITEM, args("selectionIndex", 45));
        // 99 超出 45 位志愿表容量：路由层仍交给工具（执行层给出 1-45 的稳定报错）
        assertRoutes("把第 99 所加入志愿单", AgentToolNames.ADD_PLAN_ITEM, args("selectionIndex", 99));
    }

    // ---------- removePlanItem（确认流） ----------

    @Test
    void removePlanItem_requiresExplicitConfirmation() {
        // 2026-09 审计：'把第 1 项从志愿单删除'曾因量词"项"未覆盖而完全未路由
        AgentDecision d = decide("把第 1 项从志愿单删除");
        assertEquals(AgentToolNames.REPLY, d.getAction());
        assertTrue(d.getReply().contains("确认删除"));
    }

    @Test
    void removePlanItem_confirmedWithPendingContext_executes() {
        // 确认流依赖最近消息里的确认提示（AgentChatService 真实流程会提供）
        AgentDecision d = service.decide("确认删除第 1 个", pendingDeleteContext(1), null);
        assertEquals(AgentToolNames.REMOVE_PLAN_ITEM, d.getAction());
        assertEquals("1", String.valueOf(d.getToolArgs().get("selectionIndex")));
    }

    @Test
    void removePlanItem_confirmedWithoutPendingContext_rejects() {
        AgentDecision d = decide("确认删除");
        assertEquals(AgentToolNames.REPLY, d.getAction());
        assertTrue(d.getReply().contains("没有检测到"));
    }

    // ---------- savePlan ----------

    @Test
    void savePlan_withName() {
        assertRoutes("把当前志愿单保存为审计方案", AgentToolNames.SAVE_PLAN, args("planName", "审计方案"));
    }

    @Test
    void savePlan_withoutName_asksForName() {
        AgentDecision d = decide("保存方案");
        assertEquals(AgentToolNames.REPLY, d.getAction());
        assertTrue(d.getReply().contains("方案名"));
    }

    // ---------- 负向防护：不得误触发工具 ----------

    @ParameterizedTest(name = "[负例] {0} → REPLY")
    @CsvSource({
            "今天天气怎么样",
            "我刚把第3条志愿删除了",
            "看看能不能上浙江大学",
            "我620分想去北京上大学"
    })
    void negativeCases_mustNotTriggerTools(String phrase) {
        assertRoutes(phrase, AgentToolNames.REPLY, null);
    }

    // ---------- fixtures ----------

    /** 构造真实删除确认上下文：用户删除请求 → 助手确认提示（匹配逻辑要求两段连续）。 */
    private List<AgentMessage> pendingDeleteContext(int selectionIndex) {
        AgentMessage userMessage = new AgentMessage();
        userMessage.setRole(AgentRoles.USER);
        userMessage.setMessageType(AgentMessageTypes.TEXT);
        userMessage.setContent("把第 %d 项从志愿单删除".formatted(selectionIndex));
        AgentMessage prompt = new AgentMessage();
        prompt.setRole(AgentRoles.ASSISTANT);
        prompt.setMessageType(AgentMessageTypes.TEXT);
        prompt.setContent("删除是敏感操作。若确认删除当前志愿单中的第 %d 个结果，请回复“确认删除第%d个”。"
                .formatted(selectionIndex, selectionIndex));
        return List.of(userMessage, prompt);
    }
}
