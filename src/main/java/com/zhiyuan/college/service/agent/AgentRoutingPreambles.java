package com.zhiyuan.college.service.agent;

import java.util.Map;

/**
 * 工具调用前的确定性说明话术（按工具与参数生成）。
 *
 * <p>原本是 AgentDecisionService 的私有方法；校准路由层（SemanticRouterService）
 * 产生与本地规划器同口吻的调用说明时也要用，为避免 router → decision service 的
 * 构造器循环依赖（Spring 启动会失败），抽成无状态静态工具类供双方委托。
 */
public final class AgentRoutingPreambles {

    private AgentRoutingPreambles() {
    }

    /** 工具调用的一句说明；模型/路由未给话术时的确定性兜底（与本地规划器口吻一致）。 */
    public static String forTool(String action, Map<String, Object> args) {
        return switch (action) {
            case AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME -> "我先按学校名帮你查询\u201c%s\u201d的详情和可参考专业。"
                    .formatted(textArg(args, "universityName", "目标院校"));
            case AgentToolNames.GET_SCHOOL_DETAIL -> "我先帮你查看第 %s 个学校的详情和可参考专业。"
                    .formatted(textArg(args, "selectionIndex", "1"));
            case AgentToolNames.GET_MAJOR_OVERVIEW -> "我先查询“%s”的学习内容、就业方向和报考提醒。"
                    .formatted(textArg(args, "majorKeyword", "相关方向"));
            case AgentToolNames.RECOMMEND_MAJORS -> "我先基于你的画像和\u201c%s\u201d的兴趣给你生成专业推荐。"
                    .formatted(textArg(args, "majorKeyword", "你感兴趣的方向"));
            case AgentToolNames.RECOMMEND_SCHOOLS -> "我先基于你当前画像给你生成学校推荐。";
            case AgentToolNames.GET_USER_PROFILE -> "我先帮你读取当前画像信息。";
            case AgentToolNames.GET_CURRENT_PLAN -> "我先帮你查看当前志愿方案。";
            case AgentToolNames.ADD_PLAN_ITEM -> "我先把最近推荐里的第 %s 个结果加入当前志愿单。"
                    .formatted(textArg(args, "selectionIndex", "1"));
            case AgentToolNames.REMOVE_PLAN_ITEM -> "我现在删除当前志愿单中的第 %s 个结果。"
                    .formatted(textArg(args, "selectionIndex", "1"));
            case AgentToolNames.SAVE_PLAN -> "我现在把当前志愿单保存为《%s》。"
                    .formatted(textArg(args, "planName", "新方案"));
            default -> "正在调用工具。";
        };
    }

    private static String textArg(Map<String, Object> args, String name, String fallback) {
        if (args == null) {
            return fallback;
        }
        Object value = args.get(name);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }
}
