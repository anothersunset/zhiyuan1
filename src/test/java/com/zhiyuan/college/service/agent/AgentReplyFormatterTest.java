package com.zhiyuan.college.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 回复格式化契约：不同工具载荷应渲染成对应的结构化回复。
 * 校情卡片（阶段修复）：getSchoolDetail / getSchoolDetailByName 的载荷此前
 * 只回显一行摘要，用户看到的不是"该校专业列表"。
 */
@DisplayName("Agent 回复格式化")
class AgentReplyFormatterTest {

    private final AgentReplyFormatter formatter = new AgentReplyFormatter(new ObjectMapper());

    private AgentToolResult schoolDetailResult(String payloadJson) {
        return AgentToolResult.success(AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME, "已按学校名查询 湘潭大学 的详情。", payloadJson);
    }

    @Test
    void schoolDetail_rendersMajorTableWithTierAndProvince() {
        String payload = """
                {
                  "queryType": "by_name",
                  "universityName": "湘潭大学",
                  "universityProvince": "湖南",
                  "universityTier": "本科一批",
                  "is985": false,
                  "is211": false,
                  "isDoubleFirstClass": true,
                  "schoolTags": ["双一流"],
                  "majorCount": 33,
                  "majors": [
                    {"majorName": "电子信息工程", "cutoffScore": 598, "minRank": 19898},
                    {"majorName": "物理学", "cutoffScore": 595, "minRank": 21396}
                  ]
                }
                """;
        String reply = formatter.format(schoolDetailResult(payload), null);

        assertTrue(reply.contains("## 湘潭大学"), "校名标题");
        assertTrue(reply.contains("院校层次：双一流"), "层次标签（schoolTags 与 isDoubleFirstClass 去重合并）");
        assertFalse(reply.contains("双一流 / 双一流"), "去重");
        assertTrue(reply.contains("所在省份：湖南"));
        assertTrue(reply.contains("共 33 个"));
        assertTrue(reply.contains("| 电子信息工程 | 598 | 19898 |"));
        assertTrue(reply.contains("| 物理学 | 595 | 21396 |"));
        assertTrue(reply.contains("电子信息工程专业怎么样"), "下一步引导");
    }

    @Test
    void schoolDetail_tierFallsBackToUniversityTier_whenNoFlags() {
        String payload = """
                {
                  "universityName": "测试学院",
                  "universityProvince": "湖北",
                  "universityTier": "本科二批",
                  "majorCount": 2,
                  "majors": [{"majorName": "会计学", "cutoffScore": null, "minRank": null}]
                }
                """;
        String reply = formatter.format(schoolDetailResult(payload), null);

        assertTrue(reply.contains("院校层次：本科二批"));
        assertTrue(reply.contains("| 会计学 | — | — |"), "空分数渲染为占位符");
    }

    @Test
    void schoolDetail_emptyMajors_fallsBackToSummary() {
        String payload = """
                {"universityName": "测试学院", "majorCount": 0, "majors": []}
                """;
        String reply = formatter.format(schoolDetailResult(payload), null);
        assertEquals("已按学校名查询 湘潭大学 的详情。", reply);
    }

    @Test
    void schoolDetail_byOrdinalPayload_rendersSameCard() {
        String payload = """
                {
                  "selectionIndex": 2,
                  "universityName": "湖南师范大学",
                  "universityProvince": "湖南",
                  "isDoubleFirstClass": true,
                  "majorCount": 36,
                  "majors": [{"majorName": "教育学", "cutoffScore": 581, "minRank": 24100}]
                }
                """;
        AgentToolResult result = AgentToolResult.success(AgentToolNames.GET_SCHOOL_DETAIL, "已查询详情。", payload);
        String reply = formatter.format(result, null);
        assertTrue(reply.contains("## 湖南师范大学"));
        assertTrue(reply.contains("| 教育学 | 581 | 24100 |"));
    }

    @Test
    void recommendationPayloads_stillRenderAsReport() {
        String payload = """
                {
                  "recommendationMode": "SCHOOL_FIRST",
                  "userRank": 12000,
                  "topItems": [{"universityName": "大学A", "majorName": "专业A", "group": "rush",
                                "schoolTags": ["985"], "cutoffScore": 600, "admissionProbability": 40}]
                }
                """;
        String reply = formatter.format(AgentToolResult.success(
                AgentToolNames.RECOMMEND_SCHOOLS, "已生成推荐。", payload), null);
        assertTrue(reply.contains("冲稳保院校推荐"), "推荐载荷不受校情卡片影响");
    }

    @Test
    void majorOverview_overviewMarkdown_takesPrecedence() {
        String payload = """
                {"majorName": "考古学", "overviewMarkdown": "## 考古学专业概览\\n内容"}
                """;
        String reply = formatter.format(AgentToolResult.success(
                AgentToolNames.GET_MAJOR_OVERVIEW, "已查询。", payload), null);
        assertTrue(reply.startsWith("## 考古学专业概览"));
    }
}
