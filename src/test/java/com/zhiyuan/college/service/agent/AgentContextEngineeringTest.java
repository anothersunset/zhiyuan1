package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.model.entity.AgentMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文装配契约（context engineering，参考 Claude Code / ZCode 的纪律）：
 * 大载荷出带、两级历史压缩、截断保护、系统快照就位。
 */
@DisplayName("Agent 上下文装配")
class AgentContextEngineeringTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();
    private final AgentDecisionService service = new AgentDecisionService(
            null, new ObjectMapper(), registry, false);

    private AgentMessage message(String role, String type, String toolName, String content, String payloadJson) {
        AgentMessage message = new AgentMessage();
        message.setRole(role);
        message.setMessageType(type);
        message.setToolName(toolName);
        message.setContent(content);
        message.setPayloadJson(payloadJson);
        return message;
    }

    private String recommendPayload(int count) {
        StringBuilder items = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) items.append(',');
            items.append("{\"label\":\"大学").append(i).append("\",\"group\":\"safe\"}");
        }
        return items.append(']').toString();
    }

    private String buildPrompt(List<AgentMessage> messages) {
        return service.buildUserPrompt("当前用户消息", messages, null);
    }

    @Test
    void recommendationPayload_neverEntersPromptVerbatim() {
        String bigPayload = "{\"topItems\":" + recommendPayload(55)
                + ",\"recommendationMode\":\"SCHOOL_FIRST\",\"totalCount\":55}";
        List<AgentMessage> messages = List.of(
                message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "帮我推荐学校", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_CALL,
                        AgentToolNames.RECOMMEND_SCHOOLS, "我先基于你当前画像给你生成学校推荐。", null),
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TOOL_RESULT,
                        AgentToolNames.RECOMMEND_SCHOOLS, "已筛选出 55 所院校。", bigPayload));
        String prompt = buildPrompt(messages);
        // 原始载荷绝不出现
        assertFalse(prompt.contains("大学55") && prompt.contains("\"topItems\""), "原始 JSON 泄漏进提示词");
        // 摘要指针就位
        assertTrue(prompt.contains("推荐载荷共 55 项"), "应包含载荷摘要");
        assertTrue(prompt.contains("完整数据由执行层按需取用"));
    }

    @Test
    void olderMessages_areCompressed_recentKeptVerbatim() {
        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            messages.add(message(AgentRoles.USER, AgentMessageTypes.TEXT, null, "历史消息" + i, null));
        }
        String prompt = buildPrompt(messages);
        assertTrue(prompt.contains("更早 6 条消息，摘要"), "应有摘要段");
        assertTrue(prompt.contains("以上为摘要，以下为最近消息原文"), "应有原文分界");
        // 最近 6 条保留原文；更早 6 条被压缩成单行摘要（"- [role/type] 内容"格式）
        assertTrue(prompt.contains(": 历史消息12"), "最近一条应保留原文");
        assertFalse(prompt.contains(": 历史消息1\n"), "最早消息应只出现在摘要段（无原文行）");
    }

    @Test
    void longContent_truncated() {
        String longText = "很长的回复".repeat(200);
        List<AgentMessage> messages = List.of(
                message(AgentRoles.ASSISTANT, AgentMessageTypes.TEXT, null, longText, null));
        String prompt = buildPrompt(messages);
        assertFalse(prompt.contains(longText), "长内容应被截断");
        assertTrue(prompt.contains("…"), "截断应有省略号");
    }

    @Test
    void snapshot_and_profile_stillPresent() {
        String prompt = buildPrompt(List.of());
        assertTrue(prompt.contains("系统实时状态"));
        assertTrue(prompt.contains("用户画像"));
    }
}
