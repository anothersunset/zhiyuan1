package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.service.AiChatClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 决策层与校准路由的端到端接线验收：
 * 默认关闭零回归、active 高置信短路（且本地规划器仍最优先）、低置信回落旧路径、
 * 删除确认 Noul 独立开关生效且 fail-closed。
 */
@DisplayName("AgentDecisionService × 校准路由接线")
class AgentDecisionServiceCalibratedRoutingTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiChatClient aiChatClient = mock(AiChatClient.class);

    private static class StubProvider implements CalibratedRouteProvider {
        CalibratedRoute route;
        Boolean deletion;
        int routeCalls;

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public CalibratedRoute chooseTool(AgentDialogState state, String stateContext) {
            routeCalls++;
            return route;
        }

        @Override
        public Boolean confirmsDeletion(String userMessage, int selectionIndex) {
            return deletion;
        }
    }

    private AgentDecisionService serviceWith(SemanticRouterService router, boolean qwenEnabled) {
        return new AgentDecisionService(aiChatClient, objectMapper, registry,
                new AgentIntentLexicon(objectMapper), new AgentMajorCatalogService(null),
                router, qwenEnabled, true);
    }

    // ---------- 默认关闭：与旧版行为一致 ----------

    @Test
    void routerDisabled_fuzzyMessageFallsToLegacyDefaultReply() {
        // qwen 关闭 + 路由关闭 → 与改造前的行为逐字一致
        AgentDecisionService service = serviceWith(SemanticRouterService.disabled(), false);

        AgentDecision decision = service.decide("在吗随便聊聊今天的天气真好", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertTrue(decision.getReply().contains("还没理解"));
    }

    // ---------- active：高置信短路，低置信回落 ----------

    @Test
    void activeRouting_highConfidence_shortCircuitsWithoutLlm() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.95);
        SemanticRouterService router = new SemanticRouterService(List.of(provider), registry,
                true, "active", 0.9, 4000L, "stub", false);
        AgentDecisionService service = serviceWith(router, false); // qwen 关闭：若路由未生效只会得到默认回复

        AgentDecision decision = service.decide("在吗随便聊聊今天的天气真好", List.of(), null);

        assertEquals(AgentToolNames.RECOMMEND_SCHOOLS, decision.getAction());
        verify(aiChatClient, never()).chatWithTools(anyString(), anyString(), anyDouble(), anyList());
        verify(aiChatClient, never()).chat(anyString(), anyString(), anyDouble(), anyBoolean());
    }

    @Test
    void activeRouting_lowConfidence_fallsThroughToLegacy() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.5);
        SemanticRouterService router = new SemanticRouterService(List.of(provider), registry,
                true, "active", 0.9, 4000L, "stub", false);
        AgentDecisionService service = serviceWith(router, false);

        AgentDecision decision = service.decide("在吗随便聊聊今天的天气真好", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertTrue(decision.getReply().contains("还没理解"));
    }

    @Test
    void localPlanner_stillWinsOverCalibratedRoute() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.GET_USER_PROFILE, Map.of(), 0.99);
        SemanticRouterService router = new SemanticRouterService(List.of(provider), registry,
                true, "active", 0.9, 4000L, "stub", false);
        AgentDecisionService service = serviceWith(router, false);

        AgentDecision decision = service.decide("我的画像是什么", List.of(), null);

        assertEquals(AgentToolNames.GET_USER_PROFILE, decision.getAction());
        assertEquals(0, provider.routeCalls, "本地强意图命中时路由层不应被消费");
    }

    @Test
    void activeRouting_routedDestructiveCall_stillGuarded() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.REMOVE_PLAN_ITEM,
                Map.of("selectionIndex", 2), 0.99);
        SemanticRouterService router = new SemanticRouterService(List.of(provider), registry,
                true, "active", 0.9, 4000L, "stub", false); // 语义确认关闭
        AgentDecisionService service = serviceWith(router, false);

        AgentDecision decision = service.decide("帮我把第2个删了吧", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction(), "路由层给的删除调用同样必须过代码级闸门");
        assertTrue(decision.getReply().contains("确认删除第2个"));
    }

    // ---------- 影子模式：决策不变，旁路记录 ----------

    @Test
    void shadowRouting_decisionUnchanged_comparisonRecorded() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.95);
        SemanticRouterService router = new SemanticRouterService(List.of(provider), registry,
                true, "shadow", 0.9, 4000L, "stub", false);
        AgentDecisionService service = serviceWith(router, false);

        AgentDecision decision = service.decide("在吗随便聊聊今天的天气真好", List.of(), null);

        assertEquals(AgentToolNames.REPLY, decision.getAction(), "影子模式必须保持旧决策");
        assertEquals(1, provider.routeCalls, "影子对比恰好旁路一次");
    }

    // ---------- 删除确认 Noul（独立于路由开关） ----------

    @Test
    void deleteSemanticConfirm_explicitConfirmation_allowsParaphrase() {
        StubProvider provider = new StubProvider();
        provider.deletion = Boolean.TRUE;
        SemanticRouterService router = new SemanticRouterService(List.of(provider), registry,
                false, "shadow", 0.9, 4000L, "stub", true);
        AgentDecisionService service = serviceWith(router, true);
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(
                        AgentToolNames.REMOVE_PLAN_ITEM, "{\"selectionIndex\":2}", ""));

        AgentDecision decision = service.decide("帮我把第2个删了吧", List.of(), null);

        assertEquals(AgentToolNames.REMOVE_PLAN_ITEM, decision.getAction(),
                "高置信明确确认时，闸门应放行（旧路径此处会要求逐字确认）");
        assertEquals("2", String.valueOf(decision.getToolArgs().get("selectionIndex")));
    }

    @Test
    void deleteSemanticConfirm_deniedOrUncertain_failsClosed() {
        StubProvider denying = new StubProvider();
        denying.deletion = Boolean.FALSE;
        assertEquals(AgentToolNames.REPLY,
                decideRemove(denying).getAction(), "明确否认 → 走旧确认提示");

        StubProvider unsure = new StubProvider();
        unsure.deletion = null;
        AgentDecision unsureDecision = decideRemove(unsure);
        assertEquals(AgentToolNames.REPLY, unsureDecision.getAction(), "无意见 → fail-closed");
        assertTrue(unsureDecision.getReply().contains("确认删除第2个"));
    }

    private AgentDecision decideRemove(StubProvider provider) {
        SemanticRouterService router = new SemanticRouterService(List.of(provider), registry,
                false, "shadow", 0.9, 4000L, "stub", true);
        AgentDecisionService service = serviceWith(router, true);
        when(aiChatClient.chatWithTools(anyString(), anyString(), anyDouble(), anyList()))
                .thenReturn(new AiChatClient.NativeToolCall(
                        AgentToolNames.REMOVE_PLAN_ITEM, "{\"selectionIndex\":2}", ""));
        return service.decide("帮我把第2个删了吧", List.of(), null);
    }
}
