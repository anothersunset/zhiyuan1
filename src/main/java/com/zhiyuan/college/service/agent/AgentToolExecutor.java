package com.zhiyuan.college.service.agent;

import com.zhiyuan.college.model.entity.AgentMessage;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentToolExecutor {

    private static final int MAX_SELECTION_INDEX = 45;

    private final AgentToolRegistry agentToolRegistry;
    private final AgentToolFacade agentToolFacade;

    public AgentToolExecutor(AgentToolRegistry agentToolRegistry,
                             AgentToolFacade agentToolFacade) {
        this.agentToolRegistry = agentToolRegistry;
        this.agentToolFacade = agentToolFacade;
    }

    public AgentToolResult execute(Long userId, String toolName, Map<String, Object> toolArgs, List<AgentMessage> recentMessages) {
        return execute(userId, null, toolName, toolArgs, recentMessages, null);
    }

    public AgentToolResult execute(Long userId,
                                   Long targetPlanId,
                                   String toolName,
                                   Map<String, Object> toolArgs,
                                   List<AgentMessage> recentMessages) {
        return execute(userId, targetPlanId, toolName, toolArgs, recentMessages, null);
    }

    public AgentToolResult execute(Long userId,
                                   Long targetPlanId,
                                   String toolName,
                                   Map<String, Object> toolArgs,
                                   List<AgentMessage> recentMessages,
                                   java.util.function.Consumer<String> onChunk) {
        if (!agentToolRegistry.supports(toolName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported agent tool for execution: " + toolName);
        }
        validateToolArgs(toolName, toolArgs);
        return switch (toolName) {
            case AgentToolNames.GET_USER_PROFILE -> agentToolFacade.getUserProfile(userId);
            case AgentToolNames.GET_CURRENT_PLAN -> agentToolFacade.getCurrentPlan(userId, targetPlanId);
            case AgentToolNames.GET_SCHOOL_DETAIL -> agentToolFacade.getSchoolDetail(userId, toolArgs, recentMessages);
            case AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME -> agentToolFacade.getSchoolDetailByName(userId, toolArgs);
            case AgentToolNames.GET_MAJOR_OVERVIEW -> agentToolFacade.getMajorOverview(
                    toolArgs == null ? null : toolArgs.get("majorKeyword")
            );
            case AgentToolNames.RECOMMEND_SCHOOLS -> agentToolFacade.recommendSchools(userId, onChunk);
            case AgentToolNames.RECOMMEND_MAJORS -> agentToolFacade.recommendMajors(
                    userId,
                    toolArgs == null ? null : toolArgs.get("majorKeyword"),
                    onChunk
            );
            case AgentToolNames.ADD_PLAN_ITEM -> agentToolFacade.addPlanItem(userId, targetPlanId, toolArgs, recentMessages);
            case AgentToolNames.REMOVE_PLAN_ITEM -> agentToolFacade.removePlanItem(userId, targetPlanId, toolArgs);
            case AgentToolNames.SAVE_PLAN -> agentToolFacade.savePlan(userId, targetPlanId, toolArgs);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported agent tool for execution: " + toolName);
        };
    }

    /**
     * 规格驱动的参数校验：必填性、类型与取值范围全部来自 AgentToolRegistry 的声明，
     * 校验消息保持稳定（前端与 LLM 决策提示词依赖这些语义）。
     * removePlanItem 的 selectionIndex 为必填——删除绝不允许默认猜序号。
     */
    private void validateToolArgs(String toolName, Map<String, Object> toolArgs) {
        AgentToolSpec spec = agentToolRegistry.getSpec(toolName);
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported agent tool for execution: " + toolName);
        }
        for (AgentToolParamSpec param : spec.parameters()) {
            Object value = toolArgs == null ? null : toolArgs.get(param.name());
            boolean blank = value == null || (value instanceof String s && s.isBlank());
            if (blank) {
                if (param.required()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            param.name() + " is required for " + toolName);
                }
                continue;
            }
            if ("int".equals(param.type())) {
                int number = parseInteger(value, param.name());
                if (number < param.minValue() || number > param.maxValue()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            param.name() + " must be between " + param.minValue() + " and " + param.maxValue());
                }
            } else {
                String text = String.valueOf(value).trim();
                if (text.length() < param.minLength() || text.length() > param.maxLength()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            param.name() + " must be between " + param.minLength() + " and "
                                    + param.maxLength() + " characters");
                }
            }
        }
    }

    private int parseInteger(Object value, String fieldName) {
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be an integer");
        }
    }
}
