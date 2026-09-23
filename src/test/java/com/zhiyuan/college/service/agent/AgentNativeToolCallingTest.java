package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.model.entity.AgentMessage;
import com.zhiyuan.college.service.AiChatClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 原生 tool-calling 兜底层的验收测试：LLM 模糊语义决策从"提示词约束输出 JSON"
 * 升级为 OpenAI function-calling（DeepSeek tools 参数同形状）。
 *
 * <p>覆盖：工具声明派生、tool_calls 解析、reply 合成工具、未知工具兜底、
 * JSON 模式降级、删除确认代码级闸门与总开关。
 */
@DisplayName("Agent 原生 tool-calling 兜底")
class AgentNativeToolCallingTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiChatClient aiChatClient = mock(AiChatClient.class);

    private AgentDecisionService nativeService() {
        return new AgentDecisionService(aiChatClient, objectMapper, registry, true);
    }

    // ---------- 工具声明派生（AgentToolSpec → OpenAI function-calling） ----------

    @Test
    void openAiDefinitions_coverReplyPlusAllTenSpecs() {
        List<Map<String, Object>> definitions = registry.getOpenAiToolDefinitions();

        assertEquals(11, definitions.size(), "合成 reply 工具 + 10 个白名单工具");
        Map<String, Object> first = definitions.get(0);
        assertEquals("function", first.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> replyFunction = (Map<String, Object>) first.get("function");
        assertEquals(AgentToolNames.REPLY, replyFunction.get("name"));

        for (AgentToolSpec spec : registry.listSpecs()) {
            boolean found = definitions.stream().anyMatch(definition -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> function = (Map<String, Object>) definition.get("function");
                return spec.name().equals(function.get("name"));
            });
            assertTrue(found, () -> "规格 " + spec.name() + " 缺少原生声明");
        }
    }

    @Test
    void openAiDefinitions_encodeRangesAsJsonSchema() {
        Map<String, Object> definitions = registry.getOpenAiToolDefinitions().stream()
                .filter(definition -> AgentToolNames.RECOMMEND_MAJORS.equals(
                        ((Map<?, ?>) definition.get("function")).get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) ((Map<String, Object>) definitions.get("function")).get("parameters");
        @SuppressWarnings("unchecked")
        Map<String, Object> majorKeyword = (Map<String, Object>) ((Map<String, Object>) parameters.get("properties")).get("majorKeyword");
        assertEquals("string", majorKeyword.get("type"));
        assertEquals(1, majorKeyword.get("minLength"));
        assertEquals(20, majorKeyword.get("maxLength"));
        assertEquals(List.of("majorKeyword"), parameters.get("required"));

        Map<String, Object> schoolDetail = registry.getOpenAiToolDefinitions().stream()
                .filter(definition -> AgentToolNames.GET_SCHOOL_DETAIL.equals(
                        ((Map<?, ?>) definition.get("function")).get("name")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> detailParameters = (Map<String, Object>) ((Map<String, Object>) schoolDetail.get("function")).get("parameters");
        @SuppressWarnings("unchecked")
        Map<String, Object> selectionIndex = (Map<String, Object>) ((Map<String, Object>) detailParameters.get("properties")).get("selectionIndex");
        assertEquals("integer", selectionIndex.get("type"));
        assertEquals(1, selectionIndex.get("minimum"));
        assertEquals(45, selectionIndex.get("maximum"));
        assertFalse(((List<?>) detailParameters.get("required")).contains("selectionIndex"),
                "可选参数不得进入 required");
    }

    // ---------- 决策解析 ----------

    @Test
    void fuzzyPhrasing_routesViaNativeToolCall() {
        // 本地规划器对"有没有适合文科生的靠谱专业呀"无命中 → 走 LLM 原生兜底
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(
                        AgentToolNames.RECOMMEND_MAJORS, "{\"majorKeyword\":\"文科\"}", "好的，我来查文科方向。"));

        AgentDecision decision = nativeService().decide("有没有适合文科生的靠谱专业呀", List.of(), null);

        assertEquals(AgentToolNames.RECOMMEND_MAJORS, decision.getAction());
        assertEquals("文科", decision.getToolArgs().get("majorKeyword"));
        assertEquals("好的，我来查文科方向。", decision.getReply());
    }

    @Test
    void nativeReplyToolCall_mapsToReply() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(
                        AgentToolNames.REPLY, "{\"reply\":\"想看哪一类专业？\"}", ""));

        AgentDecision decision = nativeService().decide("随便聊聊志愿的事", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertEquals("想看哪一类专业？", decision.getReply());
    }

    @Test
    void nativeReplyWithoutArgs_fallsBackToContent() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(AgentToolNames.REPLY, "{}", "你好，我是志愿助手。"));

        AgentDecision decision = nativeService().decide("你好呀", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertEquals("你好，我是志愿助手。", decision.getReply());
    }

    @Test
    void plainContentWithoutToolCall_mapsToReply() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(null, null, "今天想聊聊什么？"));

        AgentDecision decision = nativeService().decide("在吗", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertEquals("今天想聊聊什么？", decision.getReply());
    }

    @Test
    void unknownToolName_fallsBackToDefaultReply() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall("queryWeather", "{}", ""));

        AgentDecision decision = nativeService().decide("明天天气怎么样", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertTrue(decision.getReply().contains("还没理解"));
    }

    @Test
    void nativeFailure_fallsBackToJsonMode() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenThrow(new IllegalStateException("tools not supported by upstream"));
        when(aiChatClient.chat(anyString(), anyString(), eq(0.1), eq(true)))
                .thenReturn("{\"action\":\"recommendSchools\",\"reply\":\"好的\"}");

        AgentDecision decision = nativeService().decide("有没有适合文科生的靠谱专业呀", List.of(), null);

        assertEquals(AgentToolNames.RECOMMEND_SCHOOLS, decision.getAction());
    }

    @Test
    void nativeToolCallWithoutContent_getsDeterministicPreamble() {
        // DeepSeek 常在 tool_calls 时不带 content：执行层气泡应显示参数化话术而非"正在调用工具。"
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(
                        AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, "{\"universityName\":\"湘潭大学\"}", ""));

        AgentDecision decision = nativeService().decide("有没有适合文科生的靠谱专业呀", List.of(), null);

        assertEquals(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, decision.getAction());
        assertEquals("我先按学校名帮你查询“湘潭大学”的详情和可参考专业。", decision.getReply());
    }

    @Test
    void nativeToolCallingDisabled_skipsNativePath() {
        AgentDecisionService service = new AgentDecisionService(aiChatClient, objectMapper, registry,
                new AgentIntentLexicon(objectMapper), new AgentMajorCatalogService(null),
                SemanticRouterService.disabled(), true, false);
        when(aiChatClient.chat(anyString(), anyString(), eq(0.1), eq(true)))
                .thenReturn("{\"action\":\"getCurrentPlan\",\"reply\":\"好的\"}");

        AgentDecision decision = service.decide("我现在方案里都有啥呀", List.of(), null);

        assertEquals(AgentToolNames.GET_CURRENT_PLAN, decision.getAction());
        verify(aiChatClient, never()).chatWithTools(anyString(), anyString(), anyDouble(), anyList());
    }

    // ---------- 删除确认代码级闸门（原生与 JSON 模式共用） ----------

    @Test
    void nativeRemovePlanItem_withoutConfirmation_downgradedToConfirmPrompt() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(
                        AgentToolNames.REMOVE_PLAN_ITEM, "{\"selectionIndex\":2}", ""));

        AgentDecision decision = nativeService().decide("帮我把第2个删了吧", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertTrue(decision.getReply().contains("确认删除第2个"));
    }

    @Test
    void nativeRemovePlanItem_withPendingConfirmation_passes() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(
                        AgentToolNames.REMOVE_PLAN_ITEM, "{\"selectionIndex\":2}", ""));

        AgentDecision decision = nativeService().decide("帮我把第2个删了吧", pendingDeleteContext(2), null);

        assertEquals(AgentToolNames.REMOVE_PLAN_ITEM, decision.getAction());
        assertEquals("2", String.valueOf(decision.getToolArgs().get("selectionIndex")));
    }

    @Test
    void jsonModeRemovePlanItem_withoutConfirmation_downgradedToConfirmPrompt() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenThrow(new IllegalStateException("tools not supported by upstream"));
        when(aiChatClient.chat(anyString(), anyString(), eq(0.1), eq(true)))
                .thenReturn("{\"action\":\"removePlanItem\",\"reply\":\"好的\",\"toolArgs\":{\"selectionIndex\":3}}");

        AgentDecision decision = nativeService().decide("把第三个移除", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertTrue(decision.getReply().contains("确认删除第3个"));
    }

    // ---------- 本地规划器优先级不回归 ----------

    @Test
    void localPlannerStillShortCircuits_beforeNativeCall() {
        AgentDecision decision = nativeService().decide("查看当前志愿方案", List.of(), null);

        assertEquals(AgentToolNames.GET_CURRENT_PLAN, decision.getAction());
        verify(aiChatClient, never()).chatWithTools(anyString(), anyString(), anyDouble(), anyList());
        verify(aiChatClient, never()).chat(anyString(), anyString(), eq(0.1), eq(true));
    }

    @Test
    void nativeSystemPrompt_keepsRoutingConstraints_withoutJsonContract() {
        // 反射读取私有方法不可取；这里直接断言行为：模糊语料仍由原生路径处理且收到的
        // 系统提示词不含旧 JSON 契约字样（通过 mock 捕获参数）。
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(AgentToolNames.REPLY, "{\"reply\":\"ok\"}", ""));
        nativeService().decide("有没有适合文科生的靠谱专业呀", List.of(), null);

        org.mockito.ArgumentCaptor<String> systemCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiChatClient).chatWithTools(systemCaptor.capture(), anyString(), anyDouble(), anyList());
        String systemPrompt = systemCaptor.getValue();
        assertFalse(systemPrompt.contains("\"toolArgs\""), "原生模式不得再要求输出 JSON 契约");
        assertTrue(systemPrompt.contains("recommendSchools"), "路由约束保留");
        assertTrue(systemPrompt.contains("removePlanItem"), "删除确认约束保留");
    }

    @Test
    void malformedArguments_doNotCrashDecision() {
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(
                        AgentToolNames.RECOMMEND_MAJORS, "not-json{{", ""));

        AgentDecision decision = nativeService().decide("有没有适合文科生的靠谱专业呀", List.of(), null);

        assertEquals(AgentToolNames.RECOMMEND_MAJORS, decision.getAction());
        assertTrue(decision.getToolArgs().isEmpty());
        // 执行层会以"majorKeyword is required"稳定报错，而不是 500
        assertNotEquals(AgentToolNames.REPLY, decision.getAction());
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
