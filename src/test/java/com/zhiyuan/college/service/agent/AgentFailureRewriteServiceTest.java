package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyuan.college.service.AiChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 工具失败→LLM 改写兜底的验收：白名单、fail-open、开关回退。 */
@DisplayName("AgentFailureRewriteService（失败改写）")
class AgentFailureRewriteServiceTest {

    private final AiChatClient aiChatClient = mock(AiChatClient.class);

    @Test
    void readOnlyToolFailure_getsRewritten() {
        when(aiChatClient.chat(anyString(), anyString(), eq(0.3), eq(false)))
                .thenReturn("（以下为通用信息，非本系统数据）软件工程主要学习……");

        AgentFailureRewriteService service = new AgentFailureRewriteService(aiChatClient, true);

        assertTrue(service.isRewritable(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME));
        String text = service.rewrite("查中南大学软件工程专业录取分",
                AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, "{\"universityName\":\"查中南大学\"}", "当前数据集暂未收录该校");
        assertTrue(text != null && text.contains("非本系统数据"));
    }

    @Test
    void planMutationTools_neverRewritten() {
        AgentFailureRewriteService service = new AgentFailureRewriteService(aiChatClient, true);

        assertTrue(!service.isRewritable(AgentToolNames.REMOVE_PLAN_ITEM));
        assertTrue(!service.isRewritable(AgentToolNames.ADD_PLAN_ITEM));
        assertTrue(!service.isRewritable(AgentToolNames.SAVE_PLAN));
        assertNull(service.rewrite("x", AgentToolNames.REMOVE_PLAN_ITEM, "{}", "y"),
                "志愿单增/删/存失败必须保持确定性引导话术");
    }

    @Test
    void llmFailure_fallsBackToNull() {
        when(aiChatClient.chat(anyString(), anyString(), anyDouble(), eq(false)))
                .thenThrow(new IllegalStateException("upstream down"));
        AgentFailureRewriteService service = new AgentFailureRewriteService(aiChatClient, true);

        assertNull(service.rewrite("x", AgentToolNames.GET_MAJOR_OVERVIEW, "{}", "y"));
    }

    @Test
    void disabled_skipsLlmCompletely() {
        AgentFailureRewriteService service = new AgentFailureRewriteService(aiChatClient, false);

        assertNull(service.isRewritable(AgentToolNames.GET_SCHOOL_DETAIL) ? "bad" : null);
        assertNull(service.rewrite("x", AgentToolNames.GET_SCHOOL_DETAIL, "{}", "y"));
        verify(aiChatClient, org.mockito.Mockito.never())
                .chat(anyString(), anyString(), anyDouble(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
