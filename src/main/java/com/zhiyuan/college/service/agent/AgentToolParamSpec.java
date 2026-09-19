package com.zhiyuan.college.service.agent;

/**
 * 单个工具参数的机器可读规格（对齐 OpenAI Function Calling 的 JSON Schema 参数声明
 * 与 Spring AI {@code @ToolParam} 的理念：参数的必填性、类型与取值范围只声明一次，
 * 提示词文档、参数校验与契约测试均由此派生）。
 *
 * @param name       参数名（与 toolArgs JSON 键一致）
 * @param type       "int" 或 "string"
 * @param required   是否必填；false 时缺省行为由工具实现定义（如 selectionIndex 默认 1）
 * @param description 给 LLM 看的参数说明
 * @param minValue   int 型下界（含）
 * @param maxValue   int 型上界（含）
 * @param minLength  string 型最短长度（含）
 * @param maxLength  string 型最长长度（含）
 */
public record AgentToolParamSpec(
        String name,
        String type,
        boolean required,
        String description,
        Integer minValue,
        Integer maxValue,
        Integer minLength,
        Integer maxLength) {

    public static AgentToolParamSpec requiredInt(String name, String description, int min, int max) {
        return new AgentToolParamSpec(name, "int", true, description, min, max, null, null);
    }

    public static AgentToolParamSpec optionalInt(String name, String description, int min, int max) {
        return new AgentToolParamSpec(name, "int", false, description, min, max, null, null);
    }

    public static AgentToolParamSpec requiredString(String name, String description, int minLen, int maxLen) {
        return new AgentToolParamSpec(name, "string", true, description, null, null, minLen, maxLen);
    }
}
