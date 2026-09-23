package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.model.entity.AgentMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 阶段② DialogState 专项语义测试：合并前的 5 处反向扫描各有独立的"首个命中即停"
 * 规则与信任边界，统一为单次遍历后必须逐条等价。
 */
@DisplayName("Agent DialogState 统一状态构建")
class AgentDialogStateTest {

    private final AgentIntentLexicon lexicon = new AgentIntentLexicon(new ObjectMapper());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentDialogState build(String message, List<AgentMessage> messages) {
        return AgentDialogState.build(message, messages, null, lexicon, objectMapper);
    }

    private AgentMessage message(String role, String type, String toolName, String content, String payloadJson) {
        AgentMessage message = new AgentMessage();
        message.setRole(role);
        message.setMessageType(type);
        message.setToolName(toolName);
        message.setContent(content);
        message.setPayloadJson(payloadJson);
        return message;
    }

    // ---------- 最近一轮推荐快照：解析失败也停，不回溯更早的有效载荷 ----------

    @Test
    void recommendationSnapshot_malformedNewerPayload_stopsWithoutFallingBack() {
        List<AgentMessage> messages = List.of(
                // 更早：有效载荷 3 项
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.RECOMMEND_SCHOOLS, "已筛选出 3 所院校。",
                        "{\"topItems\":[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"}]}"),
                // 更新：载荷损坏（非 JSON）
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.RECOMMEND_MAJORS, "推荐完成", "not-json{{"));
        AgentDialogState state = build("随便看看", messages);
        // 与合并前一致：最新一条即决定，损坏 → 不可用（-1），不回退到更早的有效载荷
        assertEquals(-1, state.lastRecommendationCount());
        assertFalse(state.snapshotText().contains("可用（共"));
    }

    @Test
    void recommendationSnapshot_latestValidPayload_wins() {
        List<AgentMessage> messages = List.of(
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.RECOMMEND_MAJORS, "旧推荐",
                        "{\"topItems\":[{\"label\":\"A\"}]}"),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.RECOMMEND_SCHOOLS, "新推荐",
                        "{\"topItems\":[{\"label\":\"A\"},{\"label\":\"B\"}]}"));
        AgentDialogState state = build("随便看看", messages);
        assertEquals(2, state.lastRecommendationCount());
    }

    @Test
    void recommendationSnapshot_nonRecommendationToolResult_ignored() {
        List<AgentMessage> messages = List.of(
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.GET_CURRENT_PLAN, "已读取当前方案。", null));
        AgentDialogState state = build("随便看看", messages);
        assertEquals(-1, state.lastRecommendationCount());
    }

    // ---------- 志愿单快照：首个计划类 TOOL_RESULT，不要求载荷非空 ----------

    @Test
    void planHint_firstPlanToolResultWins_evenWithEmptyPayload() {
        List<AgentMessage> messages = List.of(
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.GET_CURRENT_PLAN, "你当前还没有保存的志愿方案。", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.ADD_PLAN_ITEM, "已加入志愿单。", "{\"ok\":true}"));
        AgentDialogState state = build("看看方案", messages);
        // 最新（列表靠后）的 addPlanItem 是首个反向命中
        assertTrue(state.snapshotText().contains("已加入志愿单。"));
    }

    @Test
    void planHint_blankContent_defaultsToNoClue() {
        List<AgentMessage> messages = List.of(
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.GET_CURRENT_PLAN, "", null));
        AgentDialogState state = build("看看方案", messages);
        assertTrue(state.snapshotText().contains("暂无线索"));
    }

    // ---------- 学校续指槽：同一条消息先查 byName 载荷再查 USER 文本；最新优先 ----------

    @Test
    void schoolSlot_byNamePayloadOnLatestMessage_wins() {
        List<AgentMessage> messages = List.of(
                message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "你知道湘潭大学吗", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, "已按学校名查询。",
                        "{\"universityName\":\"湖南师范大学\"}"));
        AgentDialogState state = build("帮我推荐他的热门专业", messages);
        assertEquals("湖南师范大学", state.lastMentionedSchool());
    }

    @Test
    void schoolSlot_textScan_trustsUserMessagesOnly() {
        // 助手模板文本里的校名不可信（"已按学校名查询 X"会提取出伪校名）
        List<AgentMessage> messages = List.of(
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null,
                        "已按学校名查询 湖南师范大学 的详情，当前可参考 36 个专业。", null));
        AgentDialogState state = build("帮我推荐他的热门专业", messages);
        assertEquals(null, state.lastMentionedSchool());
    }

    @Test
    void schoolSlot_userMentionedSchool_found() {
        // 文本扫描信任用户消息；夹具用开头即校名的语料（贪婪匹配从首字起）
        List<AgentMessage> messages = List.of(
                message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "湘潭大学怎么样", null));
        AgentDialogState state = build("帮我推荐他的热门专业", messages);
        assertEquals("湘潭大学", state.lastMentionedSchool());
    }

    @Test
    void schoolSlot_greedyTextCapture_quirkPreserved() {
        // 已知历史行为（合并前即如此）：文本扫描会贪婪捕获"你知道湘潭大学"，
        // 真实流程由 byName 载荷规则先行命中而掩盖。此处固化该等价性，防止静默变化。
        List<AgentMessage> messages = List.of(
                message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "你知道湘潭大学吗", null));
        AgentDialogState state = build("帮我推荐他的热门专业", messages);
        assertEquals("你知道湘潭大学", state.lastMentionedSchool());
    }

    // ---------- 删除确认槽：只认最后一条助手文本 + 紧邻前一条 ----------

    @Test
    void deleteConfirmation_latestAssistantTextIsPrompt_withUserRequest_passes() {
        List<AgentMessage> messages = List.of(
                message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "把第 2 项从志愿单删除", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null,
                        "删除是敏感操作。若确认删除当前志愿单中的第 2 个结果，请回复“确认删除第2个”。", null));
        AgentDialogState state = build("确认删除第 2 个", messages);
        assertTrue(state.hasPendingDeleteConfirmation(2));
        assertFalse(state.hasPendingDeleteConfirmation(3), "序号不同即不匹配");
    }

    @Test
    void deleteConfirmation_olderPromptButNewerAssistantText_fails() {
        // 确认提示之后助手又说了别的：最后一条助手文本不再是确认提示 → 不放行
        List<AgentMessage> messages = List.of(
                message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "把第 2 项从志愿单删除", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null,
                        "删除是敏感操作。若确认删除当前志愿单中的第 2 个结果，请回复“确认删除第2个”。", null),
                message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "确认删除第 2 个", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null, "好的，我明白了。", null));
        AgentDialogState state = build("确认删除第 2 个", messages);
        assertFalse(state.hasPendingDeleteConfirmation(2));
    }

    @Test
    void deleteConfirmation_promptWithoutPrecedingUserRequest_fails() {
        // 提示前一条不是"删除动词+方案名词"的用户消息 → 不放行
        List<AgentMessage> messages = List.of(
                message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "帮我推荐学校", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null,
                        "删除是敏感操作。若确认删除当前志愿单中的第 1 个结果，请回复“确认删除第1个”。", null));
        AgentDialogState state = build("确认删除第 1 个", messages);
        assertFalse(state.hasPendingDeleteConfirmation(1));
    }

    // ---------- 方向填槽：只认最后一条助手文本是否为追问模板 ----------

    @Test
    void majorDirectionSlot_lastAssistantTextIsQuestion_true() {
        List<AgentMessage> messages = List.of(
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null,
                        "想看哪一类专业？告诉我方向（例如：计算机、电子信息、临床医学），我再基于你的画像生成专业推荐。", null));
        AgentDialogState state = build("不知道", messages);
        assertTrue(state.pendingMajorDirectionSlot());
    }

    @Test
    void majorDirectionSlot_lastAssistantTextIsOther_false() {
        List<AgentMessage> messages = List.of(
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null,
                        "想看哪一类专业？告诉我方向（例如：计算机、电子信息、临床医学），我再基于你的画像生成专业推荐。", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null, "好的。", null));
        AgentDialogState state = build("不知道", messages);
        assertFalse(state.pendingMajorDirectionSlot());
    }

    // ---------- 提示词：画像明细保留（不完整画像也要看到已填字段） ----------

    @Test
    void buildUserPrompt_incompleteProfileStillShowsKnownFields() {
        AgentDecisionService service = new AgentDecisionService(null, objectMapper, new AgentToolRegistry(), false);
        String prompt = service.buildUserPrompt("我620分想去北京", List.of(), null);
        // 状态快照行
        assertTrue(prompt.contains("- 用户画像：不完整"));
        // 画像明细 Map 原样保留（与合并前格式一致）
        assertTrue(prompt.contains("用户画像: {userId=null"));
        assertTrue(prompt.contains("系统实时状态"));
    }
}
