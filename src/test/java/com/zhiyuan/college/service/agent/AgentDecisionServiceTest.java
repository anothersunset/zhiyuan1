package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.model.entity.AgentMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentDecisionServiceTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();
    private final AgentDecisionService service = new AgentDecisionService(
            null, new ObjectMapper(), registry, false);

    // --- P0 #9: getUserProfile 路由收紧 ---
    @Test
    void shouldNotTriggerGetUserProfile_whenUserDescribesScore() {
        AgentDecision d = service.decide("我620分想去北京上大学", List.of(), null);
        assertNotEquals(AgentToolNames.GET_USER_PROFILE, d.getAction());
    }

    @Test
    void shouldNotTriggerGetUserProfile_whenUserDescribesProvince() {
        AgentDecision d = service.decide("我是浙江考生想学计算机", List.of(), null);
        assertNotEquals(AgentToolNames.GET_USER_PROFILE, d.getAction());
    }

    @Test
    void shouldTriggerGetUserProfile_whenUserAsksProfile() {
        AgentDecision d = service.decide("我的画像是什么", List.of(), null);
        assertEquals(AgentToolNames.GET_USER_PROFILE, d.getAction());
    }

    // --- P0 #5: getSchoolDetailByName 收紧 ---
    @Test
    void shouldNotTriggerGetSchoolDetailByName_whenUserWantsRecommendation() {
        AgentDecision d = service.decide("看看能不能上浙江大学", List.of(), null);
        assertNotEquals(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, d.getAction());
    }

    @Test
    void shouldTriggerGetSchoolDetailByName_whenUserWantsDetail() {
        AgentDecision d = service.decide("查看浙江大学详情", List.of(), null);
        assertEquals(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, d.getAction());
    }

    // --- P0 #6: getSchoolDetail 收紧 ---
    @Test
    void shouldNotTriggerGetSchoolDetail_whenNoOrdinalReference() {
        AgentDecision d = service.decide("什么专业好就业", List.of(), null);
        assertNotEquals(AgentToolNames.GET_SCHOOL_DETAIL, d.getAction());
    }

    @Test
    void shouldTriggerGetSchoolDetail_whenOrdinalAndDetail() {
        AgentDecision d = service.decide("第一个学校详情", List.of(), null);
        assertEquals(AgentToolNames.GET_SCHOOL_DETAIL, d.getAction());
    }

    // --- P1 #2: 删除提示路由收紧 ---
    @Test
    void shouldNotTriggerDeletePrompt_whenPastTense() {
        AgentDecision d = service.decide("我刚把第3条志愿删除了", List.of(), null);
        assertEquals(AgentToolNames.REPLY, d.getAction());
        assertFalse(d.getReply().contains("确认删除第"));
    }

    // --- P1 #7: recommendMajors 关键词扩展 ---
    @Test
    void shouldTriggerRecommendMajors_forAIKeyword() {
        AgentDecision d = service.decide("推荐人工智能专业", List.of(), null);
        assertEquals(AgentToolNames.RECOMMEND_MAJORS, d.getAction());
    }

    @Test
    void shouldTriggerRecommendMajors_forFinanceKeyword() {
        AgentDecision d = service.decide("推荐金融专业", List.of(), null);
        assertEquals(AgentToolNames.RECOMMEND_MAJORS, d.getAction());
    }

    @Test
    void shouldUseMajorOverview_whenUserAsksAboutMajorProspectAndCurriculum() {
        AgentDecision d = service.decide("临床医学专业怎么样？就业前景和学习内容介绍一下", List.of(), null);
        assertEquals(AgentToolNames.GET_MAJOR_OVERVIEW, d.getAction());
        assertEquals("临床医学", d.getToolArgs().get("majorKeyword"));
    }

    @Test
    void shouldKeepRecommendation_whenUserExplicitlyRequestsMajorRecommendation() {
        AgentDecision d = service.decide("推荐临床医学专业", List.of(), null);
        assertEquals(AgentToolNames.RECOMMEND_MAJORS, d.getAction());
    }

    // --- 正向用例 ---
    @Test
    void shouldTriggerGetCurrentPlan_forViewCurrentPlan() {
        AgentDecision d = service.decide("查看当前志愿方案", List.of(), null);
        assertEquals(AgentToolNames.GET_CURRENT_PLAN, d.getAction());
    }

    @Test
    void shouldTriggerSavePlan() {
        AgentDecision d = service.decide("保存为“冲稳保方案”", List.of(), null);
        assertEquals(AgentToolNames.SAVE_PLAN, d.getAction());
    }

    @Test
    void shouldTriggerAddPlanItem() {
        AgentDecision d = service.decide("加入志愿单第2个", List.of(), null);
        assertEquals(AgentToolNames.ADD_PLAN_ITEM, d.getAction());
    }

    @Test
    void shouldTriggerRecommendSchools_forChongWenBao() {
        AgentDecision d = service.decide("请生成45志愿位冲稳保方案", List.of(), null);
        assertEquals(AgentToolNames.RECOMMEND_SCHOOLS, d.getAction());
    }

    // --- 审查补充：医学精确匹配 + 形容词清洗 + 修改画像排除 ---
    @Test
    void shouldExtractPreciseClinicalMedicineKeyword() {
        AgentDecision d = service.decide("推荐临床医学专业", List.of(), null);
        assertEquals(AgentToolNames.RECOMMEND_MAJORS, d.getAction());
        assertEquals("临床医学", d.getToolArgs().get("majorKeyword"));
    }

    @Test
    void shouldCleanAdjectiveFromMajorKeyword() {
        AgentDecision d = service.decide("推荐好的计算机专业", List.of(), null);
        assertEquals(AgentToolNames.RECOMMEND_MAJORS, d.getAction());
        assertEquals("计算机", d.getToolArgs().get("majorKeyword"));
    }

    @Test
    void shouldNotTriggerGetUserProfile_whenUserWantsToEdit() {
        AgentDecision d = service.decide("修改我的信息", List.of(), null);
        assertNotEquals(AgentToolNames.GET_USER_PROFILE, d.getAction());
    }

    // --- L-20260921-08: 指代学校的具体专业问询 → 专业介绍，而不是倒学校专业表 ---

    @Test
    void pronounPlusConcreteMajorOverview_routesToMajorOverview() {
        AgentDecision d = service.decide("它的电子信息工程专业怎么样", List.of(schoolContextMessage()), null);
        assertEquals(AgentToolNames.GET_MAJOR_OVERVIEW, d.getAction());
        assertEquals("电子信息工程", d.getToolArgs().get("majorKeyword"));
    }

    @Test
    void explicitSchoolPlusConcreteMajorOverview_routesToMajorOverview() {
        AgentDecision d = service.decide("中南大学的电子信息工程专业怎么样", List.of(), null);
        assertEquals(AgentToolNames.GET_MAJOR_OVERVIEW, d.getAction());
        assertEquals("电子信息工程", d.getToolArgs().get("majorKeyword"));
    }

    @Test
    void pronounMajorQuestionWithoutConcreteMajor_stillSchoolDetail() {
        // "它的专业怎么样"没有具体专业名：保留原对话续槽行为（查该校详情）
        AgentDecision d = service.decide("它的专业怎么样", List.of(schoolContextMessage()), null);
        assertEquals(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, d.getAction());
    }

    @Test
    void ordinalPlusConcreteMajorOverview_routesToMajorOverview() {
        // 序号指代 + 具体专业名 + 概览语气：与指代词场景同语义，答专业本身（审查 P2-1 锁定）
        AgentDecision d = service.decide("第一个学校的临床医学专业怎么样", List.of(), null);
        assertEquals(AgentToolNames.GET_MAJOR_OVERVIEW, d.getAction());
        assertEquals("临床医学", d.getToolArgs().get("majorKeyword"));
    }

    @Test
    void schoolOverviewViewWithoutConcreteMajor_stillSchoolDetail() {
        // "查看/详情"类明确查看意图且无具体专业名：保持学校详情
        AgentDecision d = service.decide("查看浙江大学详情", List.of(), null);
        assertEquals(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, d.getAction());
    }

    /** byName 工具结果载荷：为对话续槽提供确定的 lastMentionedSchool（不经正则，确定性夹具）。 */
    private AgentMessage schoolContextMessage() {
        AgentMessage message = new AgentMessage();
        message.setRole(AgentRoles.TOOL);
        message.setMessageType(AgentMessageTypes.TOOL_RESULT);
        message.setToolName(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME);
        message.setPayloadJson("{\"universityName\":\"中南大学\"}");
        return message;
    }

    // --- L-20260921-09 扫描修复：动词粘名 / 否定确认 / 加入志愿单词表 ---

    @Test
    void queryVerbGluedSchoolName_extractsCleanName() {
        // "查"曾被粘进校名（universityName=查中南大学）导致"暂未收录"
        AgentDecision d = service.decide("查中南大学软件工程专业录取分", List.of(), null);
        assertEquals(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, d.getAction());
        assertEquals("中南大学", d.getToolArgs().get("universityName"));
    }

    @Test
    void negatedDeleteConfirmation_doesNotExecute() {
        AgentDecision d = service.decide("先不确认删除第2个", pendingDeleteContext(2), null);
        assertNotEquals(AgentToolNames.REMOVE_PLAN_ITEM, d.getAction());
    }

    @Test
    void addPlanWithMyWording_routesToAddPlanItem() {
        AgentDecision d = service.decide("把第 2 个加入我的志愿单", List.of(), null);
        assertEquals(AgentToolNames.ADD_PLAN_ITEM, d.getAction());
        assertEquals(2, d.getToolArgs().get("selectionIndex"));
    }

    @Test
    void llmGeneratedConfirmation_withSpaces_stillExecutes() {
        // L-20260922 多轮测试：LLM 兜底生成的确认话术带空格（"确认删除第 1 个"），
        // 用户照抄回复后本地检测必须归一化匹配，否则确认链路断裂
        AgentDecision d = service.decide("确认删除第 1 个", llmConfirmContext(), null);
        assertEquals(AgentToolNames.REMOVE_PLAN_ITEM, d.getAction());
        assertEquals(1, d.getToolArgs().get("selectionIndex"));
    }

    @Test
    void shortDeleteRequest_chainStillExecutes() {
        // 用户删除请求不带"志愿/方案"名词（"删除第1个"）→ LLM 确认 → 照抄确认也应执行
        AgentMessage userMessage = new AgentMessage();
        userMessage.setRole(AgentRoles.USER);
        userMessage.setMessageType(AgentMessageTypes.TEXT);
        userMessage.setContent("删除第1个");
        AgentMessage prompt = new AgentMessage();
        prompt.setRole(AgentRoles.ASSISTANT);
        prompt.setMessageType(AgentMessageTypes.TEXT);
        prompt.setContent("删除操作需要你确认一下：当前志愿单第 1 项是「上海师范大学」，确认删除吗？回复「确认删除第 1 个」我就为你移除。");
        AgentDecision d = service.decide("确认删除第 1 个", List.of(userMessage, prompt), null);
        assertEquals(AgentToolNames.REMOVE_PLAN_ITEM, d.getAction());
        assertEquals(1, d.getToolArgs().get("selectionIndex"));
    }

    /** LLM 兜底生成的确认提示（带空格变体）+ 前置删除请求，构成待确认上下文。 */
    private List<AgentMessage> llmConfirmContext() {
        AgentMessage userMessage = new AgentMessage();
        userMessage.setRole(AgentRoles.USER);
        userMessage.setMessageType(AgentMessageTypes.TEXT);
        userMessage.setContent("把第 2 项从志愿单删除");
        AgentMessage prompt = new AgentMessage();
        prompt.setRole(AgentRoles.ASSISTANT);
        prompt.setMessageType(AgentMessageTypes.TEXT);
        prompt.setContent("删除是不可撤销的操作，需要你明确确认一下：请回复“确认删除第 1 个”，我就把当前志愿单里的第 1 条志愿移除。");
        return List.of(userMessage, prompt);
    }

    /** 构造"用户删除请求 → 助手确认提示"两段上下文（与 native 测试同款）。 */
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
