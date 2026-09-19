package com.zhiyuan.college.service.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Agent 工具注册表：全部白名单工具的<b>唯一声明点</b>。
 *
 * <p>对齐开源 agent 的工具设计（OpenAI Function Calling 的 JSON-Schema 工具声明、
 * Spring AI 的 {@code @Tool}/{@code @ToolParam}、LangChain 的工具注册表）：
 * 工具名、功能描述与参数 Schema 在此声明一次，其余消费方全部派生——
 * <ul>
 *   <li>LLM 意图决策提示词（AgentDecisionService 生成的参数文档）；</li>
 *   <li>执行前参数校验（AgentToolExecutor 的规格驱动校验）；</li>
 *   <li>契约测试（AgentToolContractTest 断言规格与实现一致）。</li>
 * </ul>
 * 新增工具时只改本文件 + Facade 实现 + AgentToolNames 常量。
 */
@Component
public class AgentToolRegistry {

    private static final Map<String, AgentToolSpec> SPECS = buildSpecs();

    private static Map<String, AgentToolSpec> buildSpecs() {
        Map<String, AgentToolSpec> specs = new LinkedHashMap<>();
        specs.put(AgentToolNames.GET_USER_PROFILE, new AgentToolSpec(
                AgentToolNames.GET_USER_PROFILE, "读取当前用户的分数、科类、省份等画像信息", List.of()));
        specs.put(AgentToolNames.GET_CURRENT_PLAN, new AgentToolSpec(
                AgentToolNames.GET_CURRENT_PLAN, "读取当前用户最近保存的志愿方案摘要", List.of()));
        specs.put(AgentToolNames.GET_SCHOOL_DETAIL, new AgentToolSpec(
                AgentToolNames.GET_SCHOOL_DETAIL, "查看最近推荐中的某个学校详情和专业列表",
                List.of(AgentToolParamSpec.optionalInt("selectionIndex",
                        "最近推荐中的序号，默认 1", 1, 45))));
        specs.put(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, new AgentToolSpec(
                AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, "按学校名查询院校详情和专业列表",
                List.of(AgentToolParamSpec.requiredString("universityName",
                        "学校全名，如：湘潭大学", 2, 40))));
        specs.put(AgentToolNames.GET_MAJOR_OVERVIEW, new AgentToolSpec(
                AgentToolNames.GET_MAJOR_OVERVIEW,
                "查询指定专业的学习内容、就业方向与报考提醒；不生成院校录取推荐",
                List.of(AgentToolParamSpec.requiredString("majorKeyword",
                        "专业关键词，如：计算机", 1, 20))));
        specs.put(AgentToolNames.RECOMMEND_SCHOOLS, new AgentToolSpec(
                AgentToolNames.RECOMMEND_SCHOOLS, "基于当前用户画像生成学校推荐", List.of()));
        specs.put(AgentToolNames.RECOMMEND_MAJORS, new AgentToolSpec(
                AgentToolNames.RECOMMEND_MAJORS, "基于当前用户画像和专业关键词生成专业推荐",
                List.of(AgentToolParamSpec.requiredString("majorKeyword",
                        "专业关键词，如：计算机", 1, 20))));
        specs.put(AgentToolNames.ADD_PLAN_ITEM, new AgentToolSpec(
                AgentToolNames.ADD_PLAN_ITEM, "把最近一轮推荐结果中的某一项加入当前志愿单",
                List.of(AgentToolParamSpec.optionalInt("selectionIndex",
                        "最近推荐中的序号，默认 1", 1, 45))));
        specs.put(AgentToolNames.REMOVE_PLAN_ITEM, new AgentToolSpec(
                AgentToolNames.REMOVE_PLAN_ITEM, "从当前志愿单中移除某一项，必须显式确认",
                List.of(AgentToolParamSpec.requiredInt("selectionIndex",
                        "当前志愿单中的序号，必须与确认提示一致", 1, 45))));
        specs.put(AgentToolNames.SAVE_PLAN, new AgentToolSpec(
                AgentToolNames.SAVE_PLAN, "把当前志愿单保存为指定方案名",
                List.of(AgentToolParamSpec.requiredString("planName",
                        "方案名，2-30 个字符", 2, 30))));
        return Collections.unmodifiableMap(specs);
    }

    /** 全部工具规格（顺序即声明顺序）。 */
    public List<AgentToolSpec> listSpecs() {
        return List.copyOf(SPECS.values());
    }

    /** 按名称取单个规格；未知工具返回 null。 */
    public AgentToolSpec getSpec(String toolName) {
        return SPECS.get(toolName);
    }

    /** 名称 → 描述映射（历史消费方兼容）。 */
    public Map<String, String> getToolDescriptions() {
        Map<String, String> tools = new LinkedHashMap<>();
        SPECS.values().forEach(spec -> tools.put(spec.name(), spec.description()));
        return tools;
    }

    /**
     * 给 LLM 决策提示词用的参数文档（由规格自动生成，禁止手写），
     * 形如：selectionIndex（可选，整数，1-45：最近推荐中的序号，默认 1）
     */
    public String getToolSchemaMarkdown() {
        StringBuilder sb = new StringBuilder();
        SPECS.values().forEach(spec -> {
            sb.append("- ").append(spec.name()).append("（").append(spec.description()).append("）\n");
            if (spec.parameters().isEmpty()) {
                sb.append("    参数：无\n");
                return;
            }
            spec.parameters().forEach(p -> {
                String range = "int".equals(p.type())
                        ? (p.minValue() + "-" + p.maxValue())
                        : (p.minLength() + "-" + p.maxLength() + " 个字符");
                sb.append("    参数 ").append(p.name())
                        .append("（").append(p.required() ? "必填" : "可选")
                        .append("，").append(range).append("）：").append(p.description()).append("\n");
            });
        });
        return sb.toString();
    }

    public boolean supports(String toolName) {
        return SPECS.containsKey(toolName);
    }
}
