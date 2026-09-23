package com.zhiyuan.college.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Agent 意图词表注册表（阶段①：同义词外置 JSON，启动加载）。
 *
 * <p>治理规则：本类是意图路由同义词的<b>唯一运行时来源</b>——词表内容全部来自
 * {@code classpath:agent/intent-keywords.json}，代码只保留组合与排除的布尔结构。
 * 词表按组声明（组名即语义）；其中 schoolStripVerbs / schoolNamePrefixes /
 * majorKeywordPrefixes 三组是<b>顺序敏感</b>的清洗链（replace / 前缀剥离按数组顺序
 * 执行），调整顺序会改变清洗行为，其余组为无序 contains 匹配。
 *
 * <p>加载失败采取 fail-fast：词表缺失或损坏时宁可启动失败（运维立刻发现），
 * 也不让本地规划器静默降级为"全部交给 LLM"。
 */
@Component
public class AgentIntentLexicon {

    private static final Logger log = LoggerFactory.getLogger(AgentIntentLexicon.class);

    static final String DEFAULT_RESOURCE = "agent/intent-keywords.json";

    private final Map<String, List<String>> groups = new LinkedHashMap<>();
    private final List<String> majorKeywords;
    private final List<String> keywordInterrogatives;
    private final List<String> keywordStopwords;
    private final List<String> majorKeywordPrefixes;

    @Autowired
    public AgentIntentLexicon(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_RESOURCE);
    }

    /** 测试友好的资源路径注入：指向不存在的资源即触发 fail-fast。 */
    AgentIntentLexicon(ObjectMapper objectMapper, String resourcePath) {
        JsonNode root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Intent lexicon resource not found: " + resourcePath);
            }
            root = objectMapper.readTree(in);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read intent lexicon: " + resourcePath, ex);
        }
        JsonNode groupNodes = root.path("groups");
        if (!groupNodes.isObject()) {
            throw new IllegalStateException("Intent lexicon is missing the 'groups' object: " + resourcePath);
        }
        groupNodes.fieldNames().forEachRemaining(name ->
                groups.put(name, List.copyOf(stringList(groupNodes.get(name), name))));
        majorKeywords = List.copyOf(stringList(root.path("majorKeywords"), "majorKeywords"));
        keywordInterrogatives = List.copyOf(stringList(root.path("keywordInterrogatives"), "keywordInterrogatives"));
        keywordStopwords = List.copyOf(stringList(root.path("keywordStopwords"), "keywordStopwords"));
        majorKeywordPrefixes = List.copyOf(stringList(root.path("majorKeywordPrefixes"), "majorKeywordPrefixes"));
        log.info("Agent intent lexicon loaded: {} groups, {} major keywords", groups.size(), majorKeywords.size());
    }

    /** 组内任一关键词命中即 true；未知组名防御性视为未命中（契约测试兜底防拼错）。 */
    public boolean containsAny(String text, String group) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        List<String> keywords = groups.get(group);
        if (keywords == null) {
            log.warn("Unknown intent keyword group '{}', treated as no match", group);
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 有序词组（顺序敏感组必须按序消费）；组不存在返回空表。 */
    public List<String> group(String name) {
        return groups.getOrDefault(name, List.of());
    }

    public List<String> majorKeywords() {
        return majorKeywords;
    }

    public List<String> keywordInterrogatives() {
        return keywordInterrogatives;
    }

    public List<String> keywordStopwords() {
        return keywordStopwords;
    }

    /** 顺序敏感：专业关键词的修饰前缀剥离链（按数组顺序执行）。 */
    public List<String> majorKeywordPrefixes() {
        return majorKeywordPrefixes;
    }

    private List<String> stringList(JsonNode node, String name) {
        if (!node.isArray()) {
            throw new IllegalStateException("Intent lexicon field '" + name + "' must be an array");
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }
}
