package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zhiyuan.college.mapper.MajorMapper;
import com.zhiyuan.college.model.entity.Major;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 阶段③ 专业目录服务契约：关键词归一化（本地提取与 LLM 参数两条路径的汇合层）
 * 与决策层文本兜底。核心纪律——无法解析绝不硬拒绝，含糊命中绝不改写。
 */
@DisplayName("Agent 专业目录服务")
class AgentMajorCatalogServiceTest {

    private MajorMapper mapperWithNames(String... names) {
        MajorMapper mapper = mock(MajorMapper.class);
        List<Major> majors = java.util.Arrays.stream(names)
                .map(name -> {
                    Major major = new Major();
                    major.setName(name);
                    return major;
                })
                .collect(Collectors.toList());
        when(mapper.findAllOrdered()).thenReturn(majors);
        return mapper;
    }

    // ---------- resolve：汇合层归一化 ----------

    @Test
    void resolve_exactNameHit() {
        AgentMajorCatalogService service = new AgentMajorCatalogService(mapperWithNames("考古学", "计算机科学与技术"));
        assertEquals("考古学", service.resolve("考古学").orElseThrow());
        assertEquals("计算机科学与技术", service.resolve(" 计算机科学与技术 ").orElseThrow());
    }

    @Test
    void resolve_uniqueContainment_upgradesStem() {
        AgentMajorCatalogService service = new AgentMajorCatalogService(mapperWithNames("考古学", "法学"));
        assertEquals("考古学", service.resolve("考古").orElseThrow());
    }

    @Test
    void resolve_ambiguousContainment_keepsOriginal() {
        // "计算机"同时命中多个目录名：不改写（推荐引擎自己能处理这个好关键词）
        AgentMajorCatalogService service = new AgentMajorCatalogService(
                mapperWithNames("计算机科学与技术", "计算机应用技术", "法学"));
        assertTrue(service.resolve("计算机").isEmpty() || "计算机".equals(service.resolve("计算机").orElseThrow()),
                "含糊命中要么保持原样（empty → 调用方放行原关键词），要么不得改写成其他专业");
    }

    @Test
    void resolve_dirtyKeyword_wrappingCatalogName_resolvesToLongest() {
        // 本地正则提取出的脏关键词包含完整目录名 → 归一到标准名
        AgentMajorCatalogService service = new AgentMajorCatalogService(
                mapperWithNames("考古学", "数学与应用数学", "数学"));
        assertEquals("考古学", service.resolve("想学考古学的").orElseThrow());
        // 同时包含"数学"与更长的"数学与应用数学"：取最长（更具体）者
        assertEquals("数学与应用数学", service.resolve("想学数学与应用数学的").orElseThrow());
    }

    @Test
    void resolve_unresolvableKeyword_passesThrough() {
        // 修饰词、"工科"这类目录外说法：不改写也不拒绝（保住推荐引擎的优雅降级）
        AgentMajorCatalogService service = new AgentMajorCatalogService(mapperWithNames("考古学", "法学"));
        assertTrue(service.resolve("靠谱").isEmpty());
        assertTrue(service.resolve("工科").isEmpty());
        assertTrue(service.resolve("适合我的").isEmpty());
        assertTrue(service.resolve("文").isEmpty(), "单字不参与模糊匹配");
    }

    @Test
    void resolve_caseInsensitiveExact() {
        AgentMajorCatalogService service = new AgentMajorCatalogService(mapperWithNames("AI"));
        assertEquals("AI", service.resolve("ai").orElseThrow());
    }

    // ---------- findInText：决策层兜底 ----------

    @Test
    void findInText_longestCatalogNameWins() {
        AgentMajorCatalogService service = new AgentMajorCatalogService(
                mapperWithNames("考古学", "数学与应用数学", "数学"));
        assertEquals("数学与应用数学", service.findInText("数学与应用数学的就业前景怎么样").orElseThrow());
        assertEquals("考古学", service.findInText("考古学就业前景怎么样").orElseThrow());
    }

    @Test
    void findInText_ambiguousLongest_keepsEmpty() {
        // 目录中并列最长（构造同长度的两个命中）→ 保持空，交回原流程
        AgentMajorCatalogService service = new AgentMajorCatalogService(
                mapperWithNames("软件工程A", "软件工程B", "软件"));
        assertTrue(service.findInText("软件工程A和软件工程B哪个好").isEmpty());
    }

    @Test
    void findInText_noCatalogHit_returnsEmpty() {
        AgentMajorCatalogService service = new AgentMajorCatalogService(mapperWithNames("考古学"));
        assertTrue(service.findInText("推荐适合我的专业").isEmpty(), "'推荐适合我的专业'仍应走反问方向的引导");
        assertTrue(service.findInText("").isEmpty());
        assertTrue(service.findInText(null).isEmpty());
    }

    // ---------- 决策层接线：提取失败 → 目录兜底 → 路由成功 ----------

    @Test
    void decisionLayer_catalogFallback_routesMajorOverview() {
        // "考古学就业前景怎么样"：正则提取不出（无"专业/方向"后缀），词典也没有"考古学"，
        // 目录兜底命中 → 应路由 getMajorOverview 而不是落到 LLM
        AgentDecisionService service = new AgentDecisionService(
                null, new com.fasterxml.jackson.databind.ObjectMapper(), new AgentToolRegistry(),
                new AgentIntentLexicon(new com.fasterxml.jackson.databind.ObjectMapper()),
                new AgentMajorCatalogService(mapperWithNames("考古学")),
                SemanticRouterService.disabled(), false, true);

        AgentDecision decision = service.decide("考古学就业前景怎么样", List.of(), null);
        assertEquals(AgentToolNames.GET_MAJOR_OVERVIEW, decision.getAction());
        assertEquals("考古学", decision.getToolArgs().get("majorKeyword"));
    }

    @Test
    void decisionLayer_withoutCatalogHit_preservesAskDirectionUx() {
        // 空目录（mapper 失败降级）时行为与阶段③之前一致
        AgentDecisionService service = new AgentDecisionService(
                null, new com.fasterxml.jackson.databind.ObjectMapper(), new AgentToolRegistry(),
                new AgentIntentLexicon(new com.fasterxml.jackson.databind.ObjectMapper()),
                new AgentMajorCatalogService(null),
                SemanticRouterService.disabled(), false, true);

        AgentDecision decision = service.decide("推荐适合我的专业", List.of(), null);
        assertEquals(AgentToolNames.REPLY, decision.getAction());
        assertTrue(decision.getReply().contains("方向"));
    }

    // ---------- 目录缓存韧性 ----------

    @Test
    void catalogLoadFailure_degradesToPassthrough_thenRecovers() {
        MajorMapper mapper = mock(MajorMapper.class);
        when(mapper.findAllOrdered())
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(majorsOf("考古学"));
        AgentMajorCatalogService service = new AgentMajorCatalogService(mapper, 0, 0);

        assertTrue(service.resolve("考古").isEmpty(), "DB 失败应降级为原样放行");
        assertEquals("考古学", service.resolve("考古").orElseThrow(), "重试成功后应恢复归一化");
    }

    private List<Major> majorsOf(String... names) {
        return java.util.Arrays.stream(names)
                .map(name -> {
                    Major major = new Major();
                    major.setName(name);
                    return major;
                })
                .collect(Collectors.toList());
    }

    @Test
    void anyText_catalogScan_neverThrowsOnNullMapper() {
        AgentMajorCatalogService service = new AgentMajorCatalogService(null);
        assertTrue(service.findInText("考古学").isEmpty());
        assertTrue(service.resolve("考古学").isEmpty());
    }
}
