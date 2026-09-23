package com.zhiyuan.college.service.agent;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 校准语义路由层（Jev "System One" 原理的落地编排）：在本地词典规划器与
 * DeepSeek 模糊兜底之间插入一层"类型化 Choice + 校准置信度"的快速判定。
 * provider 由 {@link CalibratedRouteProvider} 抽象（当前唯一实现 deepseek-choice，
 * 换决策后端只需新增实现并登记配置）。
 *
 * <p><b>安全设计</b>（对齐 DEV_ISSUES 的错误驱动纪律，逐条对应"新层自己可能引入的故障"）：
 * <ol>
 *   <li>默认关闭：ai.agent.calibrated-routing.enabled 缺省 false，关闭时本层不参与
 *       任何决策，行为与旧版完全一致；</li>
 *   <li>fail-open：provider 异常/超时/无意见/低置信一律回落既有路径，本层永不阻塞
 *       一轮对话——最坏情况等于没有本层；</li>
 *   <li>专用有界线程池：路由调用不进 ForkJoinPool.commonPool。底层 LLM 调用挂死时
 *       可能占用线程数十秒（AiChatClient 30s 读超时 × 重试链），共享池被打满会拖垮
 *       全部请求；这里隔离为最多 4 线程 + 32 队列，饱和即快速拒绝 → fail-open。
 *       orTimeout 只放弃等待不中断底层调用，因此隔离池是必要而非可选；</li>
 *   <li>熔断（含半开）：连续 failure-threshold 次故障后冷却 cooldown；冷却结束放行
 *       <b>一次</b>探测，探测失败立即重新熔断（不让上游半死状态反复烧每条消息 4s）；
 *       provider 持续返回"无意见"（如上游劣化为垃圾 JSON）同样计入熔断，防止
 *       "响应正常但全是垃圾"的盲区；</li>
 *   <li>白名单：provider 给出的工具名必须过 AgentToolRegistry，未知工具结构性不可达，
 *       reply 永不由本层短路（生成类回复必须走既有 LLM 路径）；</li>
 *   <li>阈值钳位：act-threshold 钳到 [0.5, 0.99]，NaN 回落 0.9，配置手误也不会让
 *       低置信调用自动执行；</li>
 *   <li>删除确认 fail-closed：语义确认失败/无意见一律要求用户显式确认，与旧行为一致；
 *       只有 provider 以 ≥0.9 置信度给出明确 TRUE 才放行；</li>
 *   <li>影子模式：mode=shadow 时只做旁路对比日志，绝不参与决策（探针验证法）。
 *       注意影子调用是同步旁路（每条模糊消息多一次真实 LLM 往返），成本已知后再开。</li>
 * </ol>
 */
@Component
public class SemanticRouterService {

    private static final Logger log = LoggerFactory.getLogger(SemanticRouterService.class);

    /** act-threshold 允许的配置下界：低于 0.5 的"高置信短路"没有意义且有风险。 */
    private static final double MIN_ACT_THRESHOLD = 0.5;
    /** act-threshold 允许的配置上界：0.99 以上实际不可达，视为配置笔误。 */
    private static final double MAX_ACT_THRESHOLD = 0.99;
    /** 连续故障次数达到该值即熔断。 */
    private static final int FAILURE_THRESHOLD = 3;
    /** 默认熔断冷却时长（毫秒）。 */
    private static final long DEFAULT_COOLDOWN_MILLIS = 60_000L;
    /** 路由线程池上限。 */
    private static final int ROUTE_POOL_MAX_THREADS = 4;
    /** 路由线程池队列容量：打满即拒绝（fail-open），不无限积压。 */
    private static final int ROUTE_POOL_QUEUE_CAPACITY = 32;

    private final boolean enabled;
    private final boolean activeMode;
    private final double actThreshold;
    private final long routeTimeoutMillis;
    private final long cooldownMillis;
    private final boolean deleteConfirmEnabled;
    private final AgentToolRegistry registry;
    private final CalibratedRouteProvider provider;
    /** 连续故障计数（超时/异常）。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 连续"无意见"计数（响应正常但内容不可用，如劣化为垃圾 JSON）。 */
    private final AtomicInteger consecutiveNoOpinions = new AtomicInteger();
    private final AtomicLong breakerOpenUntilMillis = new AtomicLong();
    /** 半开探测标记：冷却结束后的下一次调用是探测，失败立即重开熔断。 */
    private final AtomicBoolean halfOpenProbe = new AtomicBoolean(false);
    private final ThreadPoolExecutor routeExecutor;

    @Autowired
    public SemanticRouterService(List<CalibratedRouteProvider> providers,
                                 AgentToolRegistry registry,
                                 @Value("${ai.agent.calibrated-routing.enabled:false}") boolean enabled,
                                 @Value("${ai.agent.calibrated-routing.mode:shadow}") String mode,
                                 @Value("${ai.agent.calibrated-routing.act-threshold:0.9}") double actThreshold,
                                 @Value("${ai.agent.calibrated-routing.route-timeout-millis:4000}") long routeTimeoutMillis,
                                 @Value("${ai.agent.calibrated-routing.provider:deepseek-choice}") String providerName,
                                 @Value("${ai.agent.delete-semantic-confirm.enabled:false}") boolean deleteConfirmEnabled) {
        this(providers, registry, enabled, mode, actThreshold, routeTimeoutMillis,
                providerName, deleteConfirmEnabled, DEFAULT_COOLDOWN_MILLIS);
    }

    /** 测试装配：可注入冷却时长以便验证熔断半开恢复路径。 */
    SemanticRouterService(List<CalibratedRouteProvider> providers,
                          AgentToolRegistry registry,
                          boolean enabled,
                          String mode,
                          double actThreshold,
                          long routeTimeoutMillis,
                          String providerName,
                          boolean deleteConfirmEnabled,
                          long cooldownMillis) {
        this.enabled = enabled;
        this.activeMode = enabled && "active".equalsIgnoreCase(mode == null ? "" : mode.trim());
        this.actThreshold = clampThreshold(actThreshold);
        this.routeTimeoutMillis = routeTimeoutMillis > 0 ? routeTimeoutMillis : 4000L;
        this.cooldownMillis = cooldownMillis > 0 ? cooldownMillis : DEFAULT_COOLDOWN_MILLIS;
        this.deleteConfirmEnabled = deleteConfirmEnabled;
        this.registry = registry;
        this.provider = resolveProvider(providers, providerName);
        this.routeExecutor = new ThreadPoolExecutor(
                2, ROUTE_POOL_MAX_THREADS, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(ROUTE_POOL_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "calibrated-route");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.routeExecutor.allowCoreThreadTimeOut(true);
        if (enabled) {
            log.info("Agent calibrated routing enabled: mode={}, actThreshold={}, provider={}, deleteSemanticConfirm={}",
                    activeMode ? "active" : "shadow", this.actThreshold,
                    provider == null ? "none" : provider.name(), deleteConfirmEnabled);
        }
    }

    /** 容器关闭时回收路由线程池（守护线程本身不阻止退出，这里只为干净停机）。 */
    @PreDestroy
    void shutdownRouteExecutor() {
        routeExecutor.shutdownNow();
    }

    /** 测试与兼容装配用的"完全禁用"实例：所有路径直接回落旧行为。 */
    public static SemanticRouterService disabled() {
        return new SemanticRouterService(List.of(), new AgentToolRegistry(),
                false, "shadow", 0.9, 4000L, "none", false);
    }

    /** active 模式：高置信时短路既有决策路径。 */
    public boolean routingActive() {
        return activeMode;
    }

    /** shadow 模式：只记录对比日志，不参与决策。 */
    public boolean shadowEnabled() {
        return enabled && !activeMode;
    }

    /** 删除确认的语义判定开关（独立于路由开关，便于单独回退）。 */
    public boolean deleteConfirmEnabled() {
        return deleteConfirmEnabled;
    }

    /**
     * active 模式下的高置信短路判定：仅当 provider 以 ≥ act-threshold 的置信度选中
     * 一个白名单业务工具时返回决策，其余一切情况返回 null（= 走既有路径）。
     */
    public AgentDecision decideIfConfident(AgentDialogState state, String stateContext) {
        if (!activeMode || provider == null || breakerOpen()) {
            return null;
        }
        CalibratedRoute route = routeWithinBudget(state, stateContext, "route");
        if (route == null || route.confidence() < actThreshold) {
            return null;
        }
        if (AgentToolNames.REPLY.equals(route.toolName())) {
            // 生成类回复必须走既有 LLM 路径，本层永不代替生成
            return null;
        }
        if (!registry.supports(route.toolName())) {
            log.warn("Agent calibrated route selected non-whitelisted tool '{}', ignored", route.toolName());
            return null;
        }
        log.info("Agent calibrated route short-circuit: tool={}, confidence={}", route.toolName(), route.confidence());
        return new AgentDecision(route.toolName(),
                AgentRoutingPreambles.forTool(route.toolName(), route.toolArgs()),
                route.toolArgs());
    }

    /**
     * shadow 模式的旁路对比：对最终实际决策记录"本层会给什么"，用于对账一致率。
     * 任何内部异常都被吞掉——影子路径绝不允许影响主流程。
     */
    public void recordShadowComparison(AgentDialogState state, String stateContext, AgentDecision actualDecision) {
        if (!shadowEnabled() || provider == null || breakerOpen() || actualDecision == null) {
            return;
        }
        try {
            CalibratedRoute route = routeWithinBudget(state, stateContext, "shadow-route");
            if (route == null) {
                return;
            }
            boolean agree = Objects.equals(actualDecision.getAction(), route.toolName());
            log.info("Agent calibrated route shadow: semantic={}/{} actual={} agree={}",
                    route.toolName(), route.confidence(), actualDecision.getAction(), agree);
        } catch (Exception ex) {
            log.debug("Agent calibrated route shadow comparison skipped: {}", ex.getMessage());
        }
    }

    /**
     * 删除确认的 Noul 语义判定：返回 true 仅当 provider 明确以高置信确认。
     * 关闭、熔断、无意见、异常全部返回 false（fail-closed，走旧路径要求确认）。
     */
    public boolean deletionConfirmedBySemantics(AgentDialogState state, int selectionIndex) {
        if (!deleteConfirmEnabled || provider == null || breakerOpen()) {
            return false;
        }
        try {
            Boolean confirmed = withBudget(
                    () -> provider.confirmsDeletion(state.normalizedMessage(), selectionIndex),
                    "delete-confirm");
            if (Boolean.TRUE.equals(confirmed)) {
                log.info("Agent delete confirmed via calibrated noul (index={})", selectionIndex);
                return true;
            }
            return false;
        } catch (Exception ex) {
            log.warn("Agent delete semantic confirm failed, fail-closed: {}", ex.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 内部：调用预算、无意见计数与熔断
    // ------------------------------------------------------------------

    /** 路由专用：带预算调用 + "无意见"计熔断（删除确认的正常"不确定"不算故障，不走这里）。 */
    private CalibratedRoute routeWithinBudget(AgentDialogState state, String stateContext, String operation) {
        AtomicBoolean budgetFailed = new AtomicBoolean(false);
        CalibratedRoute route = withBudget(() -> provider.chooseTool(state, stateContext), operation, budgetFailed);
        if (route == null) {
            if (budgetFailed.get()) {
                // 超时/异常/池拒绝已在 withBudget 里计 failures，这里不再重复计数
                return null;
            }
            // provider 正常返回 null（无意见，如上游劣化为垃圾 JSON）：半开探测的无意见
            // 视同探测失败立即重开；稳态下连续无意见达阈值同样熔断
            if (halfOpenProbe.compareAndSet(true, false)) {
                openBreaker("half-open probe returned no opinion (" + operation + ")");
                return null;
            }
            int noOpinions = consecutiveNoOpinions.incrementAndGet();
            if (noOpinions >= FAILURE_THRESHOLD) {
                openBreaker("consecutive no-opinion responses (" + operation + ")");
            }
            return null;
        }
        consecutiveNoOpinions.set(0);
        return route;
    }

    private <T> T withBudget(Supplier<T> call, String operation) {
        return withBudget(call, operation, new AtomicBoolean(false));
    }

    private <T> T withBudget(Supplier<T> call, String operation, AtomicBoolean budgetFailed) {
        CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(call::get, routeExecutor)
                    .orTimeout(routeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            // 线程池饱和/已关闭：快速拒绝即 fail-open，不排队等待
            budgetFailed.set(true);
            recordFailure(operation, ex);
            return null;
        }
        try {
            T result = future.join();
            consecutiveFailures.set(0);
            halfOpenProbe.set(false);
            return result;
        } catch (Exception ex) {
            budgetFailed.set(true);
            recordFailure(operation, ex);
            return null;
        }
    }

    private void recordFailure(String operation, Exception ex) {
        int failures = consecutiveFailures.incrementAndGet();
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        log.warn("Agent calibrated route {} failed ({}/{}): {}", operation, failures, FAILURE_THRESHOLD, cause.getMessage());
        if (halfOpenProbe.compareAndSet(true, false)) {
            // 半开探测失败：上游仍不健康，立即重开熔断，不让后续消息反复探测
            openBreaker("half-open probe failed (" + operation + ")");
            return;
        }
        if (failures >= FAILURE_THRESHOLD) {
            openBreaker("consecutive failures (" + operation + ")");
        }
    }

    private void openBreaker(String reason) {
        breakerOpenUntilMillis.set(System.currentTimeMillis() + cooldownMillis);
        consecutiveFailures.set(0);
        consecutiveNoOpinions.set(0);
        halfOpenProbe.set(false);
        log.warn("Agent calibrated route breaker OPEN for {}ms ({})", cooldownMillis, reason);
    }

    private boolean breakerOpen() {
        long until = breakerOpenUntilMillis.get();
        if (until <= 0) {
            return false;
        }
        if (System.currentTimeMillis() < until) {
            return true;
        }
        // 冷却结束：CAS 赢家放行一次探测，其余并发调用仍视为熔断中
        if (breakerOpenUntilMillis.compareAndSet(until, 0L)) {
            halfOpenProbe.set(true);
            return false;
        }
        return true;
    }

    private CalibratedRouteProvider resolveProvider(List<CalibratedRouteProvider> providers, String providerName) {
        if (providerName == null || providerName.isBlank() || "none".equalsIgnoreCase(providerName.trim())) {
            return null;
        }
        String wanted = providerName.trim();
        return providers.stream()
                .filter(p -> wanted.equalsIgnoreCase(p.name()))
                .findFirst()
                .orElseGet(() -> {
                    if (enabled) {
                        log.warn("Agent calibrated routing provider '{}' not found, routing stays inactive", wanted);
                    }
                    return null;
                });
    }

    private double clampThreshold(double configured) {
        if (Double.isNaN(configured)) {
            log.warn("Agent calibrated routing act-threshold is NaN, using default 0.9");
            return 0.9;
        }
        if (configured < MIN_ACT_THRESHOLD || configured > MAX_ACT_THRESHOLD) {
            log.warn("Agent calibrated routing act-threshold {} out of [{}, {}], clamped",
                    configured, MIN_ACT_THRESHOLD, MAX_ACT_THRESHOLD);
        }
        return Math.max(MIN_ACT_THRESHOLD, Math.min(MAX_ACT_THRESHOLD, configured));
    }
}
