package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 校准语义路由层（Jev 原理落地）的验收测试：
 * 默认关闭零行为、fail-open、白名单、置信度门控与阈值钳位、熔断、影子旁路、删除确认 fail-closed。
 */
@DisplayName("校准语义路由（System One 原理）")
class SemanticRouterServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 可编程桩 provider：按用例注入 route/deletion/异常。 */
    private static class StubProvider implements CalibratedRouteProvider {
        CalibratedRoute route;
        Boolean deletion;
        RuntimeException failure;
        int routeCalls;

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public CalibratedRoute chooseTool(AgentDialogState state, String stateContext) {
            routeCalls++;
            if (failure != null) {
                throw failure;
            }
            return route;
        }

        @Override
        public Boolean confirmsDeletion(String userMessage, int selectionIndex) {
            if (failure != null) {
                throw failure;
            }
            return deletion;
        }
    }

    private AgentDialogState state(String message) {
        return AgentDialogState.build(message, List.of(), null,
                new AgentIntentLexicon(objectMapper), objectMapper);
    }

    private SemanticRouterService router(StubProvider provider, boolean enabled, String mode,
                                         double threshold, boolean deleteConfirm) {
        return new SemanticRouterService(
                provider == null ? List.of() : List.of(provider),
                new AgentToolRegistry(), enabled, mode, threshold, 4000L, "stub", deleteConfirm);
    }

    // ---------- 默认关闭：零行为 ----------

    @Test
    void disabledInstance_isFullyInert() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.99);
        SemanticRouterService router = SemanticRouterService.disabled();

        assertFalse(router.routingActive());
        assertFalse(router.shadowEnabled());
        assertFalse(router.deleteConfirmEnabled());
        assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"));
        assertFalse(router.deletionConfirmedBySemantics(state("确认删除第1个"), 1));
        router.recordShadowComparison(state("随便聊聊"), "ctx",
                new AgentDecision(AgentToolNames.REPLY, "ok"));
        assertEquals(0, provider.routeCalls, "禁用态不得触碰 provider");
    }

    // ---------- active 模式：只有高置信白名单业务工具才短路 ----------

    @Test
    void activeMode_highConfidenceBusinessTool_shortCircuits() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_MAJORS,
                Map.of("majorKeyword", "计算机"), 0.95);
        SemanticRouterService router = router(provider, true, "active", 0.9, false);

        AgentDecision decision = router.decideIfConfident(state("帮我推荐计算机专业吧"), "ctx");

        assertEquals(AgentToolNames.RECOMMEND_MAJORS, decision.getAction());
        assertEquals("计算机", decision.getToolArgs().get("majorKeyword"));
        assertTrue(decision.getReply().contains("专业推荐"), "话术由确定性兜底生成");
    }

    @Test
    void activeMode_belowThreshold_fallsThrough() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.8);
        SemanticRouterService router = router(provider, true, "active", 0.9, false);

        assertNull(router.decideIfConfident(state("推荐学校"), "ctx"));
    }

    @Test
    void activeMode_replyTool_neverShortCircuits() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.REPLY, Map.of("reply", "你好"), 0.99);
        SemanticRouterService router = router(provider, true, "active", 0.9, false);

        assertNull(router.decideIfConfident(state("你好"), "ctx"), "生成类回复必须走既有 LLM 路径");
    }

    @Test
    void activeMode_unknownTool_whitelisted() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute("queryWeather", Map.of(), 0.99);
        SemanticRouterService router = router(provider, true, "active", 0.9, false);

        assertNull(router.decideIfConfident(state("明天天气"), "ctx"), "未知工具结构性不可达");
    }

    @Test
    void activeMode_providerWithoutOpinion_fallsThrough() {
        StubProvider provider = new StubProvider();
        provider.route = null;
        SemanticRouterService router = router(provider, true, "active", 0.9, false);

        assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"));
    }

    @Test
    void activeMode_providerThrows_failsOpen() {
        StubProvider provider = new StubProvider();
        provider.failure = new IllegalStateException("upstream down");
        SemanticRouterService router = router(provider, true, "active", 0.9, false);

        assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"));
    }

    @Test
    void activeMode_unknownProviderName_staysInactive() {
        SemanticRouterService router = new SemanticRouterService(
                List.of(new StubProvider()), new AgentToolRegistry(),
                true, "active", 0.9, 4000L, "no-such-provider", false);

        assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"), "provider 未解析成功时路由保持不活跃");
    }

    // ---------- 阈值钳位：配置手误不产生危险行为 ----------

    @Test
    void threshold_belowFloor_clampedToHalf() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.6);
        // 配置 0.3 会被钳到下界 0.5：0.6 ≥ 0.5 允许短路
        SemanticRouterService router = router(provider, true, "active", 0.3, false);
        assertEquals(AgentToolNames.RECOMMEND_SCHOOLS,
                router.decideIfConfident(state("推荐学校"), "ctx").getAction());

        StubProvider strict = new StubProvider();
        strict.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.45);
        SemanticRouterService clamped = router(strict, true, "active", 0.3, false);
        assertNull(clamped.decideIfConfident(state("推荐学校"), "ctx"), "0.45 低于钳位下界 0.5，不得短路");
    }

    @Test
    void threshold_aboveCeilingAndNaN_clamped() {
        // 上界：0.999 → 0.99，0.995 ≥ 0.99 允许、0.98 < 0.99 拒绝
        StubProvider high = new StubProvider();
        high.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.995);
        assertEquals(AgentToolNames.RECOMMEND_SCHOOLS,
                router(high, true, "active", 0.999, false).decideIfConfident(state("推荐学校"), "ctx").getAction());

        StubProvider mid = new StubProvider();
        mid.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.98);
        assertNull(router(mid, true, "active", 0.999, false).decideIfConfident(state("推荐学校"), "ctx"));

        // NaN 配置 → 回落默认 0.9
        StubProvider atDefault = new StubProvider();
        atDefault.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.9);
        assertEquals(AgentToolNames.RECOMMEND_SCHOOLS,
                router(atDefault, true, "active", Double.NaN, false)
                        .decideIfConfident(state("推荐学校"), "ctx").getAction());
    }

    // ---------- 熔断：连续故障后冷却，不给每条消息加延迟 ----------

    @Test
    void consecutiveFailures_openBreakerAndSkipProvider() {
        StubProvider provider = new StubProvider();
        provider.failure = new IllegalStateException("down");
        SemanticRouterService router = router(provider, true, "active", 0.9, false);

        for (int i = 0; i < 3; i++) {
            assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"));
        }
        int callsAfterBreakerPoint = provider.routeCalls;
        assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"), "熔断期间直接跳过");
        assertEquals(callsAfterBreakerPoint, provider.routeCalls, "熔断期间不得再调用 provider");
    }

    @Test
    void routeTimeout_countsAsFailureAndTripsBreaker() throws InterruptedException {
        StubProvider slow = new StubProvider() {
            @Override
            public CalibratedRoute chooseTool(AgentDialogState state, String stateContext) {
                routeCalls++;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.99);
            }
        };
        // 预算 100ms，桩内睡 300ms → 前三次按超时计失败，第四次被熔断挡住
        SemanticRouterService router = new SemanticRouterService(List.of(slow), new AgentToolRegistry(),
                true, "active", 0.9, 100L, "stub", false);

        for (int i = 0; i < 4; i++) {
            assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"), "超时必须 fail-open");
        }
        awaitRouteCalls(slow, 3);
        assertEquals(3, slow.routeCalls, "熔断后不得再产生新的路由调用（入队任务执行完为止）");
    }

    /** 第 3 个任务可能仍在队列中等待线程：等它真正执行完再断言，消除与超时判定的竞态。 */
    private static void awaitRouteCalls(StubProvider provider, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (provider.routeCalls < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void halfOpen_probeFailure_reopensBreakerImmediately() throws Exception {
        StubProvider provider = new StubProvider();
        provider.failure = new IllegalStateException("down");
        SemanticRouterService router = new SemanticRouterService(List.of(provider), new AgentToolRegistry(),
                true, "active", 0.9, 4000L, "stub", false, 100L);

        for (int i = 0; i < 3; i++) {
            assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"));
        }
        int callsWhenOpened = provider.routeCalls;
        Thread.sleep(150); // 冷却结束 → 半开放行一次探测

        assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"), "探测失败 fail-open");
        assertEquals(callsWhenOpened + 1, provider.routeCalls, "半开只放行一次探测");
        assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"), "探测失败必须立即重开熔断");
        assertEquals(callsWhenOpened + 1, provider.routeCalls, "重开期间不得再调用 provider");
    }

    @Test
    void halfOpen_probeSuccess_closesBreaker() throws Exception {
        StubProvider provider = new StubProvider();
        provider.failure = new IllegalStateException("down");
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.95);
        SemanticRouterService router = new SemanticRouterService(List.of(provider), new AgentToolRegistry(),
                true, "active", 0.9, 4000L, "stub", false, 100L);

        for (int i = 0; i < 3; i++) {
            assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"));
        }
        provider.failure = null;
        Thread.sleep(150); // 冷却结束 → 半开

        assertEquals(AgentToolNames.RECOMMEND_SCHOOLS,
                router.decideIfConfident(state("随便聊聊"), "ctx").getAction(), "探测成功应恢复短路");
        assertEquals(AgentToolNames.RECOMMEND_SCHOOLS,
                router.decideIfConfident(state("随便聊聊"), "ctx").getAction(), "恢复后继续正常路由");
    }

    @Test
    void consecutiveNoOpinions_tripsBreakerAgainstGarbageUpstream() {
        StubProvider provider = new StubProvider();
        provider.route = null; // 上游劣化为"永远给不出可用答案"
        SemanticRouterService router = router(provider, true, "active", 0.9, false);

        for (int i = 0; i < 3; i++) {
            assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"), "无意见 fail-open");
        }
        assertNull(router.decideIfConfident(state("随便聊聊"), "ctx"));
        assertEquals(3, provider.routeCalls, "持续无意见也应熔断，不得无限烧上游");
    }

    // ---------- 影子模式：只旁路记录，绝不参与决策 ----------

    @Test
    void shadowMode_neverShortCircuits_butRecordsComparison() {
        StubProvider provider = new StubProvider();
        provider.route = new CalibratedRoute(AgentToolNames.RECOMMEND_SCHOOLS, Map.of(), 0.99);
        SemanticRouterService router = router(provider, true, "shadow", 0.9, false);

        assertFalse(router.routingActive());
        assertTrue(router.shadowEnabled());
        assertNull(router.decideIfConfident(state("推荐学校"), "ctx"), "影子模式不得短路");

        router.recordShadowComparison(state("推荐学校"), "ctx",
                new AgentDecision(AgentToolNames.RECOMMEND_SCHOOLS, "好的"));
        assertEquals(1, provider.routeCalls, "影子对比应恰好旁路调用一次");

        router.recordShadowComparison(state("推荐学校"), "ctx", null);
        assertEquals(1, provider.routeCalls, "实际决策为 null 时跳过对比");
    }

    @Test
    void shadowMode_providerThrows_neverPropagates() {
        StubProvider provider = new StubProvider();
        provider.failure = new IllegalStateException("down");
        SemanticRouterService router = router(provider, true, "shadow", 0.9, false);

        router.recordShadowComparison(state("随便聊聊"), "ctx",
                new AgentDecision(AgentToolNames.REPLY, "ok"));
        assertTrue(router.shadowEnabled(), "影子旁路异常不得影响任何状态");
    }

    // ---------- 删除确认 Noul：fail-closed ----------

    @Test
    void deleteConfirm_explicitTrueOnly() {
        StubProvider provider = new StubProvider();
        provider.deletion = Boolean.TRUE;
        SemanticRouterService router = router(provider, false, "shadow", 0.9, true);
        assertTrue(router.deletionConfirmedBySemantics(state("对，就删第2个"), 2));

        StubProvider denying = new StubProvider();
        denying.deletion = Boolean.FALSE;
        assertFalse(router(denying, false, "shadow", 0.9, true)
                .deletionConfirmedBySemantics(state("再想想"), 2));

        StubProvider unsure = new StubProvider();
        unsure.deletion = null;
        assertFalse(router(unsure, false, "shadow", 0.9, true)
                .deletionConfirmedBySemantics(state("删吧"), 2), "无意见必须 fail-closed");

        StubProvider broken = new StubProvider();
        broken.failure = new IllegalStateException("down");
        assertFalse(router(broken, false, "shadow", 0.9, true)
                .deletionConfirmedBySemantics(state("对删"), 2), "异常必须 fail-closed");

        StubProvider unused = new StubProvider();
        assertFalse(router(unused, false, "shadow", 0.9, false)
                .deletionConfirmedBySemantics(state("对删"), 2), "开关关闭时不调用 provider");
        assertEquals(0, unused.routeCalls);
    }
}
