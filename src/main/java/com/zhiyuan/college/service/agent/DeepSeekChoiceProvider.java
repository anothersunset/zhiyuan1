package com.zhiyuan.college.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.service.AiChatClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 在项目自有的 DeepSeek 通道上实现 Jev 的 Choice/Noul 语义（只借鉴原理，不引入外部
 * 模型服务）：严格 JSON 输出 + 校准置信度。
 *
 * <p>错误防御（对应"新层自己会引入什么故障"清单）：
 * <ul>
 *   <li>上游异常/超时 → 直接抛给路由层统一 fail-open，本类不吞；</li>
 *   <li>输出非法 JSON / 缺字段 / 空白 → 返回 null（无意见）；</li>
 *   <li>幻觉出未知工具名 → 白名单校验丢弃；</li>
 *   <li>置信度缺失/越界 → 按 0.0 处理（= 无意见，永不短路）。</li>
 * </ul>
 */
@Component
public class DeepSeekChoiceProvider implements CalibratedRouteProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChoiceProvider.class);

    /** 删除确认（Noul）放行的最低置信度——破坏性操作固定 0.9，不随路由阈值配置变化。 */
    private static final double DELETE_CONFIRM_CONFIDENCE = 0.9;
    /** 删除确认给出明确 FALSE 的最低置信度；低于此值返回 null（宁可多问一次）。 */
    private static final double DELETE_DENY_CONFIDENCE = 0.5;

    private final AiChatClient aiChatClient;
    private final ObjectMapper objectMapper;
    private final AgentToolRegistry registry;

    @Autowired
    public DeepSeekChoiceProvider(AiChatClient aiChatClient,
                                  ObjectMapper objectMapper,
                                  AgentToolRegistry registry) {
        this.aiChatClient = aiChatClient;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "deepseek-choice";
    }

    @Override
    public CalibratedRoute chooseTool(AgentDialogState state, String stateContext) {
        String content = aiChatClient.chat(buildSystemPrompt(), buildUserPrompt(stateContext), 0.1, true);
        return parseRoute(content);
    }

    @Override
    public Boolean confirmsDeletion(String userMessage, int selectionIndex) {
        String systemPrompt = """
                你是高考志愿助手的删除确认校准器。判断用户消息是否在明确确认删除志愿单中的第 %d 项。

                必须只输出 JSON：
                {"confirmed": true或false, "confidence": 0到1的小数}

                校准规则：
                - confidence 是校准概率：0.9 表示十个类似说法里约九个确实是明确确认；
                - "确认删除第2个"、"对，就删第2个"、"嗯删吧第2个" 是明确确认；
                - "删了吧"（未指明项）、"我再想想"、"要不要帮我删" 都不是明确确认；
                - 拿不准就输出 false 并给低置信度。删除是破坏性操作，宁可多问一次。
                """.formatted(selectionIndex);
        String userPrompt = "用户消息：" + (userMessage == null ? "" : userMessage);

        String content = aiChatClient.chat(systemPrompt, userPrompt, 0.1, true);
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            boolean confirmed = root.path("confirmed").asBoolean(false);
            double confidence = clamp(root.path("confidence").asDouble(0.0));
            if (confirmed && confidence >= DELETE_CONFIRM_CONFIDENCE) {
                return Boolean.TRUE;
            }
            if (!confirmed && confidence >= DELETE_DENY_CONFIDENCE) {
                return Boolean.FALSE;
            }
            return null;
        } catch (Exception ex) {
            log.warn("Agent delete-confirm output is not valid JSON: {}", abbreviate(content));
            return null;
        }
    }

    private CalibratedRoute parseRoute(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            String tool = root.path("tool").asText("").trim();
            if (tool.isEmpty()) {
                return null;
            }
            if (!AgentToolNames.REPLY.equals(tool) && !registry.supports(tool)) {
                log.warn("Agent calibrated route produced unknown tool '{}', discarded", tool);
                return null;
            }
            double confidence = clamp(root.path("confidence").asDouble(0.0));
            return new CalibratedRoute(tool, readToolArgs(root.path("toolArgs")), confidence);
        } catch (Exception ex) {
            log.warn("Agent calibrated route output is not valid JSON: {}", abbreviate(content));
            return null;
        }
    }

    private Map<String, Object> readToolArgs(JsonNode toolArgsNode) {
        if (toolArgsNode == null || toolArgsNode.isMissingNode() || toolArgsNode.isNull() || !toolArgsNode.isObject()) {
            return Collections.emptyMap();
        }
        return objectMapper.convertValue(
                toolArgsNode,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
        );
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    private String buildSystemPrompt() {
        String tools = registry.listSpecs().stream()
                .map(spec -> "- " + spec.name() + "：" + spec.description())
                .collect(Collectors.joining("\n"));
        return """
                你是高考志愿助手的校准路由器。根据对话状态从工具清单中选择唯一工具，并输出校准后的置信度。

                必须只输出 JSON：
                {"tool":"工具名或reply","confidence":0到1的小数,"toolArgs":{}}

                置信度校准规则（必须严格遵守）：
                - 0.90 以上：用户表达明确无歧义，几乎不可能有第二种理解；
                - 0.50-0.89：有倾向但存在合理歧义；
                - 0.50 以下：无法判断。
                禁止为了执行工具而抬高置信度；闲聊、澄清追问、引导补充信息或无工具匹配时选 reply 并给低置信度。

                可用工具与参数 Schema（由注册表自动生成）：
                %s
                %s
                """.formatted(tools, registry.getToolSchemaMarkdown());
    }

    private String buildUserPrompt(String stateContext) {
        return stateContext + "\n\n请输出路由 JSON。";
    }

    private String abbreviate(String content) {
        String text = content == null ? "" : content.trim();
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
