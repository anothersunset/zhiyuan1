package com.zhiyuan.college.service.agent;

import java.util.List;

/**
 * 单个工具的完整规格：名称、功能描述与参数 Schema。
 * 由 AgentToolRegistry 统一声明，供提示词生成、参数校验与契约测试派生。
 */
public record AgentToolSpec(String name, String description, List<AgentToolParamSpec> parameters) {
}
