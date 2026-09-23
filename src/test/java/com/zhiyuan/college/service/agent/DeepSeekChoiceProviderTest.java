package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.service.AiChatClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DeepSeek Choice/Noul 后端的验收测试：严格 JSON 解析、白名单、置信度钳位、
 * 非法输入一律"无意见"，上游异常按契约向上抛（由路由层 fail-open）。
 */
@DisplayName("DeepSeekChoiceProvider（Choice/Noul 语义）")
class DeepSeekChoiceProviderTest {

    private final AiChatClient aiChatClient = mock(AiChatClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeepSeekChoiceProvider provider =
            new DeepSeekChoiceProvider(aiChatClient, objectMapper, new AgentToolRegistry());

    private AgentDialogState state(String message) {
        return AgentDialogState.build(message, java.util.List.of(), null,
                new AgentIntentLexicon(objectMapper), objectMapper);
    }

    private void stubChat(String content) {
        when(aiChatClient.chat(anyString(), anyString(), anyDouble(), anyBoolean())).thenReturn(content);
    }

    // ---------- Choice 路由 ----------

    @Test
    void validRouteJson_parsesToolArgsAndConfidence() {
        stubChat("{\"tool\":\"recommendMajors\",\"confidence\":0.95,\"toolArgs\":{\"majorKeyword\":\"计算机\"}}");

        CalibratedRoute route = provider.chooseTool(state("帮我推荐计算机专业"), "ctx");

        assertEquals(AgentToolNames.RECOMMEND_MAJORS, route.toolName());
        assertEquals("计算机", route.toolArgs().get("majorKeyword"));
        assertEquals(0.95, route.confidence());
    }

    @Test
    void replyTool_isReturnedAsIs_routerDecidesPolicy() {
        stubChat("{\"tool\":\"reply\",\"confidence\":0.3,\"toolArgs\":{\"reply\":\"你好\"}}");

        CalibratedRoute route = provider.chooseTool(state("你好"), "ctx");

        assertEquals(AgentToolNames.REPLY, route.toolName());
    }

    @Test
    void unknownTool_isDiscarded() {
        stubChat("{\"tool\":\"queryWeather\",\"confidence\":0.99}");

        assertNull(provider.chooseTool(state("明天天气"), "ctx"));
    }

    @Test
    void invalidJson_isNoOpinion() {
        stubChat("我觉得应该调用 recommendSchools");

        assertNull(provider.chooseTool(state("随便聊聊"), "ctx"));
    }

    @Test
    void missingOrOutOfBoundsConfidence_treatedAsZero() {
        stubChat("{\"tool\":\"recommendSchools\"}");
        assertEquals(0.0, provider.chooseTool(state("推荐学校"), "ctx").confidence());

        stubChat("{\"tool\":\"recommendSchools\",\"confidence\":5.0}");
        assertEquals(1.0, provider.chooseTool(state("推荐学校"), "ctx").confidence(), "越界钳位到 1");

        stubChat("{\"tool\":\"recommendSchools\",\"confidence\":-1}");
        assertEquals(0.0, provider.chooseTool(state("推荐学校"), "ctx").confidence(), "负值钳位到 0");
    }

    @Test
    void blankOrMissingContent_isNoOpinion() {
        stubChat("");
        assertNull(provider.chooseTool(state("随便聊聊"), "ctx"));

        stubChat(null);
        assertNull(provider.chooseTool(state("随便聊聊"), "ctx"));
    }

    @Test
    void upstreamException_propagatesByContract() {
        when(aiChatClient.chat(anyString(), anyString(), anyDouble(), anyBoolean()))
                .thenThrow(new IllegalStateException("upstream down"));

        assertThrows(IllegalStateException.class, () -> provider.chooseTool(state("随便聊聊"), "ctx"),
                "异常必须上抛，由路由层统一 fail-open");
    }

    // ---------- Noul 删除确认 ----------

    @Test
    void deletionExplicitHighConfidence_isTrue() {
        stubChat("{\"confirmed\":true,\"confidence\":0.95}");
        assertEquals(Boolean.TRUE, provider.confirmsDeletion("对，就删第2个", 2));
    }

    @Test
    void deletionConfirmedButLowConfidence_isNull() {
        stubChat("{\"confirmed\":true,\"confidence\":0.6}");
        assertNull(provider.confirmsDeletion("删吧", 2), "确认但置信不足 → 无意见，宁可多问");
    }

    @Test
    void deletionExplicitDenial_isFalse() {
        stubChat("{\"confirmed\":false,\"confidence\":0.9}");
        assertEquals(Boolean.FALSE, provider.confirmsDeletion("我再想想", 2));
    }

    @Test
    void deletionLowConfidenceDenial_isNull() {
        stubChat("{\"confirmed\":false,\"confidence\":0.3}");
        assertNull(provider.confirmsDeletion("不知道", 2));
    }

    @Test
    void deletionInvalidJson_isNull() {
        stubChat("应该是确认的");
        assertNull(provider.confirmsDeletion("对删", 2));
    }

    @Test
    void systemPrompt_carriesToolListAndCalibrationRubric() {
        org.mockito.ArgumentCaptor<String> systemCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        stubChat("{\"tool\":\"reply\",\"confidence\":0.1}");
        provider.chooseTool(state("随便聊聊"), "ctx");

        org.mockito.Mockito.verify(aiChatClient).chat(systemCaptor.capture(), anyString(), anyDouble(), anyBoolean());
        String systemPrompt = systemCaptor.getValue();
        assertTrue(systemPrompt.contains("recommendSchools"));
        assertTrue(systemPrompt.contains("removePlanItem"));
        assertTrue(systemPrompt.contains("置信度"), "校准规则必须在提示词中");
    }
}
