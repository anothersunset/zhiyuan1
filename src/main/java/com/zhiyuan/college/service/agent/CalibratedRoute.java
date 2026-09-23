package com.zhiyuan.college.service.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 校准路由的一次结构化判定（Jev "System One" 语义：Choice = 选项 + 置信度）。
 *
 * <p>{@code confidence} 的语义约定是<b>校准过的概率</b>而不是模型口吻的"自信程度"：
 * 0.9 表示十个类似情形里约九个应如此路由。消费方（SemanticRouterService）依赖该数值
 * 做门控，因此 provider 侧必须丢弃不可解析/越界的置信度（按 0 处理 = 无意见），
 * 而不是猜测或放行。
 */
public record CalibratedRoute(String toolName, Map<String, Object> toolArgs, double confidence) {

    public CalibratedRoute {
        toolArgs = toolArgs == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(toolArgs));
    }
}
