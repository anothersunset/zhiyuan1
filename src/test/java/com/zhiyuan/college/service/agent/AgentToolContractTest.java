package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyuan.college.model.entity.AgentMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent 工具契约测试：10 个白名单工具的参数校验、默认值语义与分发边界。
 *
 * <p>治理规则：新增/修改工具参数时先在此登记契约（必填性、取值范围、默认值），
 * 校验消息必须保持稳定——前端与 LLM 决策提示词都依赖这些语义。
 */
@DisplayName("Agent 工具参数契约")
class AgentToolContractTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();
    private final AgentToolFacade facade = mock(AgentToolFacade.class);
    private final AgentToolExecutor executor = new AgentToolExecutor(registry, facade);

    // ---------- 分发边界 ----------

    @Test
    void unsupportedTool_isRejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> executor.execute(1L, "freeChat", Map.of(), List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("Unsupported agent tool"));
    }

    // ---------- 无参工具：参数可选 ----------

    @Test
    void getUserProfile_acceptsEmptyArgs() {
        when(facade.getUserProfile(anyLong())).thenReturn(AgentToolResult.success(AgentToolNames.GET_USER_PROFILE, "ok", null));
        executor.execute(1L, AgentToolNames.GET_USER_PROFILE, null, List.of());
        org.mockito.Mockito.verify(facade).getUserProfile(1L);
    }

    @Test
    void getCurrentPlan_acceptsEmptyArgs() {
        when(facade.getCurrentPlan(anyLong(), any())).thenReturn(AgentToolResult.success(AgentToolNames.GET_CURRENT_PLAN, "ok", null));
        executor.execute(1L, AgentToolNames.GET_CURRENT_PLAN, null, List.of());
        org.mockito.Mockito.verify(facade).getCurrentPlan(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    @Test
    void recommendSchools_acceptsEmptyArgs() {
        when(facade.recommendSchools(anyLong(), any())).thenReturn(AgentToolResult.success(AgentToolNames.RECOMMEND_SCHOOLS, "ok", null));
        executor.execute(1L, AgentToolNames.RECOMMEND_SCHOOLS, null, List.of());
        org.mockito.Mockito.verify(facade).recommendSchools(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    // ---------- selectionIndex 契约（getSchoolDetail/addPlanItem 可缺省=1；上限=45 位志愿表） ----------

    @Test
    void selectionIndex_defaultsToOne_whenOmitted() {
        when(facade.getSchoolDetail(anyLong(), any(), anyList()))
                .thenReturn(AgentToolResult.success(AgentToolNames.GET_SCHOOL_DETAIL, "ok", null));

        executor.execute(1L, AgentToolNames.GET_SCHOOL_DETAIL, null, List.of());

        org.mockito.Mockito.verify(facade).getSchoolDetail(org.mockito.ArgumentMatchers.eq(1L), any(), anyList());
    }

    @ParameterizedTest(name = "[selectionIndex 越界] {0}")
    @ValueSource(ints = {0, -1, 46, 99})
    void selectionIndex_outOfRange_rejectedWithStableMessage(int index) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> executor.execute(1L, AgentToolNames.GET_SCHOOL_DETAIL,
                        Map.of("selectionIndex", index), List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("selectionIndex must be between 1 and 45"));
    }

    @Test
    void selectionIndex_requiredForRemovePlanItem() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> executor.execute(1L, AgentToolNames.REMOVE_PLAN_ITEM, null, List.of()));
        assertTrue(String.valueOf(ex.getReason()).contains("selectionIndex is required for removePlanItem"));
    }

    @Test
    void selectionIndex_nonNumeric_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> executor.execute(1L, AgentToolNames.ADD_PLAN_ITEM,
                        Map.of("selectionIndex", "第一"), List.of()));
        assertTrue(String.valueOf(ex.getReason()).contains("selectionIndex"));
    }

    // ---------- universityName 契约 ----------

    @ParameterizedTest(name = "[universityName 非法] {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "清",
            "这所大学的名字特别长，已经超过了四十个字符的合理上限限制，所以必须被拒绝处理才可以的啊"})
    void universityName_illegal_rejected(String name) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> executor.execute(1L, AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME,
                        name == null ? Map.of() : Map.of("universityName", name), List.of()));
        assertTrue(String.valueOf(ex.getReason()).contains("universityName"));
    }

    // ---------- majorKeyword 契约 ----------

    @ParameterizedTest(name = "[majorKeyword 非法] {0}")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "这个专业关键词长到超过了二十个字符的上限所以必须拒绝"})
    void majorKeyword_illegal_rejected(String keyword) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> executor.execute(1L, AgentToolNames.RECOMMEND_MAJORS,
                        keyword == null ? Map.of() : Map.of("majorKeyword", keyword), List.of()));
        assertTrue(String.valueOf(ex.getReason()).contains("majorKeyword"));
    }

    // ---------- planName 契约 ----------

    @Test
    void planName_missing_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> executor.execute(1L, AgentToolNames.SAVE_PLAN, null, List.of()));
        assertTrue(String.valueOf(ex.getReason()).contains("planName is required for savePlan"));
    }

    @ParameterizedTest(name = "[planName 非法] {0}")
    @ValueSource(strings = {"A", "这个名字特别长已经超过了三十个字符的上限所以必须被拒绝才可以啊哈哈"})
    void planName_lengthOutOfBounds_rejected(String name) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> executor.execute(1L, AgentToolNames.SAVE_PLAN, Map.of("planName", name), List.of()));
        assertTrue(String.valueOf(ex.getReason()).contains("planName must be between 2 and 30"));
    }

    // ---------- 注册表白名单 ----------

    @Test
    void registry_exposesExactlyTheTenWhitelistedTools() {
        var descriptions = registry.getToolDescriptions();
        assertEquals(10, descriptions.size(), () -> "白名单工具数变更时须同步：提示词/文档/测试");
        descriptions.keySet().forEach(name -> assertTrue(registry.supports(name)));
    }
}
