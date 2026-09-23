package com.zhiyuan.college.service.agent;

/**
 * 校准路由的决策后端抽象：把"Jev 的原理"（类型化 Choice / Noul + 校准置信度）
 * 与具体推理通道解耦。当前唯一实现：
 * <ul>
 *   <li>{@code DeepSeekChoiceProvider}：在项目自有的 DeepSeek 通道上以严格 JSON 协议
 *       实现同一套 Choice/Noul 语义——只借鉴原理，不引入外部模型服务。</li>
 * </ul>
 * 未来若要换/加决策后端，实现本接口并在
 * {@code ai.agent.calibrated-routing.provider} 配置中登记即可，路由层零改动。
 *
 * <p>实现契约（违反即产生潜在 bug，务必遵守）：
 * <ul>
 *   <li>无意见/解析失败/上游异常一律返回 null 或直接抛出，由 SemanticRouterService
 *       统一 fail-open 回落旧路径——实现内不得自行"猜一个答案"；</li>
 *   <li>{@code toolName} 只能是 AgentToolRegistry 内的工具或 {@code reply}，
 *       其余值会被路由层白名单拦截；</li>
 *   <li>实现必须无请求间共享可变状态（参考 AiRequirementParserService 中
 *       "singleton 持有请求级 trace 导致并发互相覆盖"的教训）。</li>
 * </ul>
 */
public interface CalibratedRouteProvider {

    /** 后端名，与 ai.agent.calibrated-routing.provider 配置值匹配。 */
    String name();

    /**
     * Choice 语义：给定对话状态，从工具清单（含 reply）中选唯一工具并给校准置信度。
     * 返回 null 表示"无意见"，路由层将回落到既有决策路径。
     */
    CalibratedRoute chooseTool(AgentDialogState state, String stateContext);

    /**
     * Noul 语义：用户消息是否在<b>明确</b>确认删除志愿单第 {@code selectionIndex} 项。
     * 只有高置信的明确确认才返回 TRUE（删除是破坏性操作，宁可多问一次）；
     * 置信不足或无法判断返回 FALSE / null，调用方按旧路径要求用户显式确认。
     */
    Boolean confirmsDeletion(String userMessage, int selectionIndex);
}
