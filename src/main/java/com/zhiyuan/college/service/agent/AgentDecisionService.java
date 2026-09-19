package com.zhiyuan.college.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.model.entity.AgentMessage;
import com.zhiyuan.college.model.entity.UserAccount;
import com.zhiyuan.college.service.AiChatClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AgentDecisionService.class);
    private static final Pattern RECOMMEND_MAJOR_AFTER_PATTERN = Pattern.compile("推荐(?:一下|几个|一些)?([\\p{IsHan}A-Za-z0-9]{2,12})(?:专业|方向)");
    private static final Pattern RECOMMEND_MAJOR_BEFORE_PATTERN = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,12})(?:专业|方向).{0,8}推荐");
    private static final Pattern DIGIT_SELECTION_PATTERN = Pattern.compile("第\\s*(\\d{1,2})\\s*(?:个|所|条|项)");
    private static final Pattern SAVE_NAME_PATTERN = Pattern.compile("保存(?:为|成)?[《\u201c\\\"]?([^》\u201d\\n]{2,30})[》\u201d\\\"]?(?:方案)?");
    private static final Pattern SCHOOL_NAME_DETAIL_PATTERN = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,20}(?:大学|学院|学校))");
    private static final Pattern MAJOR_OVERVIEW_PATTERN = Pattern.compile(
            "(?:^|[，。！？?]|想了解|了解一下|请介绍|介绍一下|关于|问问|看看|查一下|帮我看看|帮我介绍)([\\p{IsHan}A-Za-z0-9]{2,16})(?:专业|方向)"
    );

    /** 常见专业关键词：精确子串匹配优先于正则，避免"推荐好的计算机专业"捕获到"好的计算机"。 */
    private static final List<String> MAJOR_KEYWORDS = List.of(
            "计算机科学与技术", "软件工程", "电子信息工程",
            "计算机", "软件", "网络", "信息安全", "法学", "护理",
            "人工智能", "AI", "机器学习", "数据科学", "大数据",
            "师范", "教育学", "汉语言", "数学", "物理",
            "电子信息", "电气工程", "自动化", "通信",
            "临床医学", "口腔医学", "中医学", "药学",
            "金融", "会计", "经济学", "工商管理",
            "机械", "土木", "建筑", "材料",
            "新能源", "集成电路", "芯片", "半导体",
            "医学"
    );

    private final AiChatClient aiChatClient;
    private final ObjectMapper objectMapper;
    private final AgentToolRegistry agentToolRegistry;
    private final boolean qwenEnabled;

    public AgentDecisionService(AiChatClient aiChatClient,
                                ObjectMapper objectMapper,
                                AgentToolRegistry agentToolRegistry,
                                @Value("${ai.qwen.enabled:true}") boolean qwenEnabled) {
        this.aiChatClient = aiChatClient;
        this.objectMapper = objectMapper;
        this.agentToolRegistry = agentToolRegistry;
        this.qwenEnabled = qwenEnabled;
    }

    public AgentDecision decide(String userMessage, List<AgentMessage> recentMessages, UserAccount user) {
        AgentDecision localDecision = decideLocally(userMessage, recentMessages);
        // Strong-intent keywords (recommend / profile / plan / delete / save / school
        // detail) are resolved locally with high precision. Let them short-circuit so
        // the tool actually runs instead of being bypassed by an LLM that prefers to
        // reply directly. Only when the local planner has no match do we ask the LLM
        // for semantic understanding of fuzzier requests.
        if (localDecision != null) {
            return localDecision;
        }
        if (!qwenEnabled) {
            return defaultReply();
        }
        try {
            String aiContent = aiChatClient.chat(
                    buildSystemPrompt(),
                    buildUserPrompt(userMessage, recentMessages, user),
                    0.1,
                    true
            );
            JsonNode root = objectMapper.readTree(aiContent);
            String action = root.path("action").asText("").trim();
            String reply = root.path("reply").asText("").trim();
            Map<String, Object> toolArgs = readToolArgs(root.path("toolArgs"));
            if (AgentToolNames.REPLY.equals(action)) {
                return new AgentDecision(AgentToolNames.REPLY, reply.isBlank() ? DEFAULT_REPLY_TEXT : reply);
            }
            if (agentToolRegistry.supports(action)) {
                return new AgentDecision(action, reply, toolArgs);
            }
        } catch (Exception ex) {
            log.warn("Agent AI decision failed, fallback to local planner: {}", ex.getMessage());
        }
        return defaultReply();
    }

    private static final String DEFAULT_REPLY_TEXT =
            "这句话我还没理解。你可以试试：“帮我推荐学校”、“看看我的画像”、“计算机专业学什么”、“把第 1 所加入志愿单”，或直接描述你的需求，我会调用对应工具完成。";

    private AgentDecision defaultReply() {
        return new AgentDecision(AgentToolNames.REPLY, DEFAULT_REPLY_TEXT);
    }

    private AgentDecision decideLocally(String userMessage, List<AgentMessage> recentMessages) {
        String normalized = userMessage == null ? "" : userMessage.trim();

        // --- #1: removePlanItem 确认删除 (unchanged) ---
        if (containsAny(normalized, "确认删除", "确定删除")) {
            int selectionIndex = extractSelectionIndex(normalized);
            if (hasPendingDeleteConfirmation(recentMessages, selectionIndex)) {
                return new AgentDecision(
                        AgentToolNames.REMOVE_PLAN_ITEM,
                        "我现在删除当前志愿单中的第 %s 个结果。".formatted(selectionIndex),
                        Map.of("selectionIndex", selectionIndex)
                );
            }
            return new AgentDecision(AgentToolNames.REPLY, "我没有检测到最近一条待确认的删除请求，请先明确告诉我要删除哪一项，再按提示确认。");
        }

        // --- P1 #2: 删除提示路由收紧 ---
        // 要求 "删除/移除" + "志愿/方案" + ("当前" 或 序号引用)
        // 排除过去时陈述，避免"我刚把第3条志愿删除了"误触发
        if (containsAny(normalized, "删除", "移除", "删掉")
                && containsAny(normalized, "志愿", "方案")
                && (containsOrdinalReference(normalized) || containsAny(normalized, "当前"))
                && !containsAny(normalized, "刚删除", "刚移除", "已经删除", "已经移除",
                                "删掉了", "移除了", "刚把", "已经把")) {
            int selectionIndex = extractSelectionIndex(normalized);
            return new AgentDecision(
                    AgentToolNames.REPLY,
                    "删除是敏感操作。若确认删除当前志愿单中的第 %s 个结果，请回复\u201c确认删除第%s个\u201d。".formatted(selectionIndex, selectionIndex)
            );
        }

        // --- #3: savePlan (unchanged) ---
        if (containsAny(normalized, "保存方案", "保存当前方案", "命名保存", "改名保存", "保存为", "另存为")) {
            String planName = extractPlanName(normalized);
            if (planName == null || planName.isBlank()) {
                return new AgentDecision(AgentToolNames.REPLY, "请直接告诉我方案名，例如：保存为\u201c冲稳保方案\u201d。");
            }
            return new AgentDecision(
                    AgentToolNames.SAVE_PLAN,
                    "我现在把当前志愿单保存为《%s》。".formatted(planName),
                    Map.of("planName", planName)
            );
        }

        // --- #4: addPlanItem (unchanged) ---
        if (containsAny(normalized, "加入志愿单", "加入当前方案", "加入方案", "加到志愿单", "加进志愿单")) {
            int selectionIndex = extractSelectionIndex(normalized);
            return new AgentDecision(
                    AgentToolNames.ADD_PLAN_ITEM,
                    "我先把最近推荐里的第 %s 个结果加入当前志愿单。".formatted(selectionIndex),
                    Map.of("selectionIndex", selectionIndex)
            );
        }

        // --- P0 #5: getSchoolDetailByName 收紧 ---
        // 要求 "查看/看看" + 校名 + "详情/信息/专业" 三者同时出现
        // 避免"看看能不能上浙大"（推荐意图）误命中
        String schoolName = extractSchoolName(normalized);
        if (!containsOrdinalReference(normalized)
                && schoolName != null
                && containsAny(normalized, "查看", "看看", "查一下", "查查")
                && !containsAny(normalized, "能不能上", "推荐", "概率")
                && (containsAny(normalized, "详情", "信息", "专业")
                    || containsAny(normalized, "查一下", "查查"))) {
            return new AgentDecision(
                    AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME,
                    "我先按学校名帮你查询\u201c%s\u201d的详情和可参考专业。".formatted(schoolName),
                    Map.of("universityName", schoolName)
            );
        }

        // --- P0 #6: getSchoolDetail 收紧 ---
        // 要求 序号引用(第N个) + "详情/信息/专业" 组合，去掉泛词单独触发
        // 避免"什么专业好就业"误命中 selectionIndex 默认1 导致答非所问
        if (containsOrdinalReference(normalized)
                && containsAny(normalized, "学校详情", "院校详情", "学校信息", "学校专业",
                               "有哪些专业", "什么专业", "详情", "信息", "专业")) {
            int selectionIndex = extractSelectionIndex(normalized);
            return new AgentDecision(
                    AgentToolNames.GET_SCHOOL_DETAIL,
                    "我先帮你查看第 %s 个学校的详情和可参考专业。".formatted(selectionIndex),
                    Map.of("selectionIndex", selectionIndex)
            );
        }

        // --- 专业介绍：必须先于推荐处理 ---
        // “XX专业怎么样 / 学什么 / 就业前景”是知识查询，不应被错误地变成
        // recommendMajors（后者会返回当前画像下的院校录取推荐）。
        String majorKeyword = extractMajorKeyword(normalized);
        if (majorKeyword == null && containsMajorOverviewCue(normalized)) {
            majorKeyword = extractMajorOverviewKeyword(normalized);
        }
        if (isMajorOverviewRequest(normalized, majorKeyword)) {
            return new AgentDecision(
                    AgentToolNames.GET_MAJOR_OVERVIEW,
                    "我先查询“%s”的学习内容、就业方向和报考提醒。".formatted(majorKeyword),
                    Map.of("majorKeyword", majorKeyword)
            );
        }

        // --- P1 #7: recommendMajors（关键词扩展见 extractMajorKeyword） ---
        if (containsAny(normalized, "推荐") && containsAny(normalized, "专业", "方向") && majorKeyword == null) {
            return new AgentDecision(AgentToolNames.REPLY,
                    "想看哪一类专业？告诉我方向（例如：计算机、电子信息、临床医学），我再基于你的画像生成专业推荐。");
        }
        if (containsAny(normalized, "推荐") && majorKeyword != null) {
            return new AgentDecision(
                    AgentToolNames.RECOMMEND_MAJORS,
                    "我先基于你的画像和\u201c%s\u201d的兴趣给你生成专业推荐。".formatted(majorKeyword),
                    Map.of("majorKeyword", majorKeyword)
            );
        }

        // --- #8: recommendSchools (unchanged) ---
        if (containsAny(normalized, "推荐学校", "学校推荐", "推荐院校", "院校推荐", "学校怎么报",
                "推荐志愿", "志愿推荐", "推荐大学", "大学推荐", "帮我报志愿", "推荐一下志愿",
                "推荐几所", "几所学校", "几所大学", "能上什么学校", "能上哪些大学", "能上哪些") ||
                (containsAny(normalized, "冲稳保") && containsAny(normalized, "志愿", "方案", "推荐", "浓度", "梯度"))) {
            return new AgentDecision(AgentToolNames.RECOMMEND_SCHOOLS, "我先基于你当前画像给你生成学校推荐。");
        }

        // --- P0 #9: getUserProfile 路由收紧 ---
        // 去掉 "分数/省份/科类" 等高频泛词，改为明确问询短语
        // 避免 "我620分想去北京" "我是浙江考生" 等自然语言请求误命中
        if (containsAny(normalized, "我的画像", "查看画像", "查看我的信息", "我的信息是什么", "我的信息有哪些",
                "我的报考信息", "报考信息是什么", "我的报名信息",
                "我是什么科类", "我的科类", "我的分数是多少", "我的分数",
                "我是哪个省份", "我的省份", "我的考生信息")
                && !containsAny(normalized, "修改", "更新", "编辑", "完善", "设置", "能上", "能报")) {
            return new AgentDecision(AgentToolNames.GET_USER_PROFILE, "我先帮你读取当前画像信息。");
        }

        // --- #10: getCurrentPlan (unchanged) ---
        // 必须含明确"查看/现有"语境，避免被"生成方案""冲稳保方案"等含"志愿/方案"的请求误触发
        if (containsAny(normalized, "当前表", "当前单", "当前志愿", "当前方案", "我的志愿", "我的方案",
                        "已有志愿", "已有方案", "看看志愿", "看看方案", "之前生成", "刚才生成",
                        "志愿单里", "我的志愿单", "志愿单有什么", "志愿单有哪些") ||
                (containsAny(normalized, "当前") && containsAny(normalized, "志愿", "方案"))) {
            return new AgentDecision(AgentToolNames.GET_CURRENT_PLAN, "我先帮你查看当前志愿方案。");
        }
        // --- 对话续槽：上一轮助手主动追问"专业方向"，本轮短回复即填槽答案 ---
        // 与"删除确认"同属任务型对话槽位：Agent 发起澄清后，下一轮按答案解释。
        if (pendingMajorDirectionSlot(recentMessages)) {
            String direction = extractMajorKeyword(normalized);
            if (direction != null) {
                return new AgentDecision(
                        AgentToolNames.RECOMMEND_MAJORS,
                        "好的，按“%s”方向基于你的画像生成专业推荐。".formatted(direction),
                        Map.of("majorKeyword", direction)
                );
            }
            if (containsAny(normalized, "不知道", "随便", "都行", "没有", "不确定")) {
                return new AgentDecision(AgentToolNames.REPLY, "那不如先看学校推荐？回复“帮我推荐学校”即可。");
            }
            return new AgentDecision(AgentToolNames.REPLY,
                    "没听清专业方向。请回复一个方向，例如：计算机、电子信息、临床医学。");
        }
        return null;
    }

    /**
     * 对话续槽感知：最近一条助手消息是否是"专业方向追问"。
     * 纯从最近消息窗口推导，与删除确认槽位同一模式，不引入额外存储。
     */
    private boolean pendingMajorDirectionSlot(List<AgentMessage> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return false;
        }
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            AgentMessage message = recentMessages.get(i);
            if (!AgentRoles.ASSISTANT.equals(message.getRole())
                    || !AgentMessageTypes.TEXT.equals(message.getMessageType())) {
                continue;
            }
            String content = safeContent(message);
            return content.contains("告诉我方向") && content.contains("专业推荐");
        }
        return false;
    }

    private boolean hasPendingDeleteConfirmation(List<AgentMessage> recentMessages, int selectionIndex) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return false;
        }

        int assistantPromptIndex = -1;
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            AgentMessage message = recentMessages.get(i);
            if (!AgentRoles.ASSISTANT.equals(message.getRole())
                    || !AgentMessageTypes.TEXT.equals(message.getMessageType())) {
                continue;
            }
            String content = safeContent(message);
            if (content.contains("确认删除第" + selectionIndex + "个")) {
                assistantPromptIndex = i;
                break;
            }
            return false;
        }

        if (assistantPromptIndex < 1) {
            return false;
        }

        AgentMessage previousUserMessage = recentMessages.get(assistantPromptIndex - 1);
        if (!AgentRoles.USER.equals(previousUserMessage.getRole())) {
            return false;
        }
        String previousContent = safeContent(previousUserMessage);
        return containsAny(previousContent, "删除", "移除") && containsAny(previousContent, "志愿", "方案");
    }

    private String buildSystemPrompt() {
        String tools = agentToolRegistry.getToolDescriptions().entrySet().stream()
                .map(entry -> "- " + entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("\\n"));
        String actionEnum = agentToolRegistry.listSpecs().stream()
                .map(AgentToolSpec::name)
                .collect(Collectors.joining(" | ")) + " | reply";
        return """
                你是高考志愿助手的受控编排器。你只能做十一种决策：
                1. 调用 getUserProfile
                2. 调用 getCurrentPlan
                3. 调用 getSchoolDetail
                4. 调用 getSchoolDetailByName
                5. 调用 getMajorOverview
                6. 调用 recommendSchools
                7. 调用 recommendMajors
                8. 调用 addPlanItem
                9. 调用 removePlanItem
                10. 调用 savePlan
                11. 直接回复

                你必须只输出 JSON：
                {
                  "action": "%s",
                  "reply": "给用户的简短说明",
                  "toolArgs": { 按下方工具参数说明提供 }
                }

                对删除类操作，如果用户没有明确确认，不要调用 removePlanItem，只返回 reply 让用户确认。

                关键路由约束（必须严格遵守）：
                1. 推荐请求必须走工具：用户说"推荐学校""推荐专业""我想报XX""我想学XX"时，必须返回 recommendSchools 或 recommendMajors，不能直接 reply 道歉。
                2. 区分"生成方案"vs"查看方案"："生成/做/来个方案"→ recommendSchools；"查看/看看当前方案"→ getCurrentPlan。
                3. 用户描述自己分数/省份/科类时不应触发 getUserProfile：除非用户明确问"我的画像是什么""我的分数记录"。
                4. "看看XX大学"在没明确详情请求时不应该调 getSchoolDetailByName："看看能不能上XX"是推荐意图，应走 recommendSchools。
                5. "XX专业怎么样"、"就业前景"、"学什么"、"课程介绍"等专业知识问题必须调用 getMajorOverview；只有明确要求“推荐专业/适合报什么专业”时才调用 recommendMajors。
                6. 用户只要求查看画像时只调用 getUserProfile，不要额外调用推荐工具。
                7. 结合"系统实时状态"选择工具：最近推荐不可用或为 0 项时，不要调用 addPlanItem/removePlanItem（应先 recommendSchools）；引用"第 N 个"时 N 不得超过最近推荐的项数。

                不要输出任何额外文本。
                可用工具与参数 Schema（由注册表自动生成）：
                %s
                """.formatted(actionEnum, agentToolRegistry.getToolSchemaMarkdown() + tools);
    }

    /**
     * 上下文装配（context engineering，参考 Claude Code / ZCode 的上下文管理纪律）：
     * <ul>
     *   <li>大载荷出带：工具结果 JSON（如 55 项推荐载荷）永不进入提示词正文，
     *       只保留摘要与条数指针——执行层从消息库按需取用完整数据；</li>
     *   <li>两级历史压缩：窗口内较早的消息压成单行摘要，最近若干条保留原文（仍截断），
     *       同样的 token 预算容纳更多轮次；</li>
     *   <li>系统实时状态快照：见 buildSystemSnapshot。</li>
     * </ul>
     */
    String buildUserPrompt(String userMessage, List<AgentMessage> recentMessages, UserAccount user) {
        int total = recentMessages == null ? 0 : recentMessages.size();
        int digestCount = Math.max(0, total - VERBATIM_HISTORY_MESSAGES);
        StringBuilder history = new StringBuilder();
        if (digestCount > 0) {
            history.append("（更早 ").append(digestCount).append(" 条消息，摘要）\n");
            for (AgentMessage message : recentMessages.subList(0, digestCount)) {
                history.append(renderDigest(message)).append('\n');
            }
            history.append("—— 以上为摘要，以下为最近消息原文 ——\n");
        }
        for (AgentMessage message : recentMessages.subList(digestCount, total)) {
            history.append(renderRecent(message)).append('\n');
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("userId", user == null ? null : user.getId());
        profile.put("username", user == null ? null : user.getUsername());
        profile.put("score", user == null ? null : user.getScore());
        profile.put("subjectType", user == null || user.getSubjectType() == null ? null : user.getSubjectType().name());
        profile.put("examProvince", user == null ? null : user.getExamProvince());
        return "用户画像: " + profile
                + "\n系统实时状态:\n" + buildSystemSnapshot(recentMessages, user)
                + "\n最近消息:\n" + history
                + "\n当前用户消息:\n" + userMessage;
    }

    /** 常量：窗口内保留原文的最近消息条数；更早的消息压成摘要。 */
    private static final int VERBATIM_HISTORY_MESSAGES = 6;

    /** 单条消息在提示词里的内容截断长度（防长 markdown 撑爆 token）。 */
    private static final int MAX_RENDERED_CONTENT_CHARS = 240;

    /** 较早消息的单行摘要（脱载荷）。 */
    private String renderDigest(AgentMessage message) {
        String extra = "";
        if (AgentMessageTypes.TOOL_RESULT.equals(message.getMessageType())
                && (AgentToolNames.RECOMMEND_SCHOOLS.equals(message.getToolName())
                    || AgentToolNames.RECOMMEND_MAJORS.equals(message.getToolName()))
                && message.getPayloadJson() != null && !message.getPayloadJson().isBlank()) {
            extra = " | " + recommendPayloadDigest(message.getPayloadJson());
        }
        return "- [%s/%s] %s%s".formatted(
                message.getRole(), message.getMessageType(),
                truncate(safeContent(message), 80), extra);
    }

    /** 最近消息的原文渲染：内容截断，载荷只保留摘要指针，完整数据留在消息库。 */
    private String renderRecent(AgentMessage message) {
        String extra = "";
        if (AgentMessageTypes.TOOL_RESULT.equals(message.getMessageType())
                && message.getPayloadJson() != null && !message.getPayloadJson().isBlank()) {
            extra = " | " + recommendPayloadDigest(message.getPayloadJson());
        }
        return "%s[%s]: %s%s".formatted(
                message.getRole(), message.getMessageType(),
                truncate(safeContent(message), MAX_RENDERED_CONTENT_CHARS), extra);
    }

    /** 从推荐载荷提取"条数 + 前几项名称"的摘要。 */
    private String recommendPayloadDigest(String payloadJson) {
        try {
            JsonNode topItems = objectMapper.readTree(payloadJson).path("topItems");
            if (!topItems.isArray()) {
                return "载荷存在";
            }
            int total = topItems.size();
            StringBuilder labels = new StringBuilder();
            for (int i = 0; i < Math.min(3, total); i++) {
                String label = topItems.get(i).path("label").asText("");
                if (!label.isBlank()) {
                    labels.append(labels.length() == 0 ? "" : "、").append(label);
                }
            }
            return "推荐载荷共 %d 项（前几项：%s；完整数据由执行层按需取用）".formatted(total, labels);
        } catch (Exception ex) {
            return "载荷存在";
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * Real-time perception of the volunteer-service system for LLM intent decisions:
     * profile completeness, whether the latest recommendation round is still
     * referenceable (and how many items), and the last known draft-sheet state.
     * Derived purely from the recent-message window and the user record, so no
     * extra service calls are needed on the decision path.
     */
    private String buildSystemSnapshot(List<AgentMessage> recentMessages, UserAccount user) {
        boolean profileComplete = user != null && user.getScore() != null
                && user.getSubjectType() != null
                && user.getExamProvince() != null && !user.getExamProvince().isBlank();
        StringBuilder sb = new StringBuilder();
        sb.append("- 用户画像：").append(profileComplete
                ? "%s/%s/%s分（完整，可直接推荐）".formatted(
                        user.getExamProvince(), user.getSubjectType().name(), user.getScore())
                : "不完整（调用推荐类工具前需先引导完善）");

        String recommendationStatus = "不可用（需先推荐才能引用“第 N 个”或加入志愿单）";
        if (recentMessages != null) {
            for (int i = recentMessages.size() - 1; i >= 0; i--) {
                AgentMessage message = recentMessages.get(i);
                if (!AgentMessageTypes.TOOL_RESULT.equals(message.getMessageType())
                        || (!AgentToolNames.RECOMMEND_SCHOOLS.equals(message.getToolName())
                            && !AgentToolNames.RECOMMEND_MAJORS.equals(message.getToolName()))
                        || message.getPayloadJson() == null || message.getPayloadJson().isBlank()) {
                    continue;
                }
                try {
                    JsonNode topItems = objectMapper.readTree(message.getPayloadJson()).path("topItems");
                    if (topItems.isArray()) {
                        recommendationStatus = "可用（共 %d 项，可用“第 N 个”引用或加入志愿单）"
                                .formatted(topItems.size());
                    }
                } catch (Exception ignored) {
                    // keep default status
                }
                break;
            }
        }
        sb.append("\n- 最近一轮推荐：").append(recommendationStatus);

        String planHint = "暂无线索（可调用 getCurrentPlan 查询）";
        if (recentMessages != null) {
            for (int i = recentMessages.size() - 1; i >= 0; i--) {
                AgentMessage message = recentMessages.get(i);
                if (AgentMessageTypes.TOOL_RESULT.equals(message.getMessageType())
                        && (AgentToolNames.GET_CURRENT_PLAN.equals(message.getToolName())
                            || AgentToolNames.ADD_PLAN_ITEM.equals(message.getToolName())
                            || AgentToolNames.REMOVE_PLAN_ITEM.equals(message.getToolName()))) {
                    String content = message.getContent();
                    planHint = content == null || content.isBlank() ? "暂无线索"
                            : content.length() > 60 ? content.substring(0, 60) + "…" : content;
                    break;
                }
            }
        }
        sb.append("\n- 志愿单最近状态：").append(planHint);
        return sb.toString();
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

    private String extractMajorKeyword(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 先精确匹配常见专业关键词：避免"推荐好的计算机专业"被正则捕获成"好的计算机"。
        // 按「最长命中优先」匹配："软件工程就业前景"应命中"软件工程"而非其子串"软件"。
        String best = null;
        for (String keyword : MAJOR_KEYWORDS) {
            if (text.contains(keyword) && (best == null || keyword.length() > best.length())) {
                best = keyword;
            }
        }
        if (best != null) {
            return best;
        }
        // 再走正则提取列表未覆盖的专业名，并清洗形容词等修饰词
        Matcher afterMatcher = RECOMMEND_MAJOR_AFTER_PATTERN.matcher(text);
        if (afterMatcher.find()) {
            String major = cleanMajorKeyword(afterMatcher.group(1));
            if (major != null) {
                return major;
            }
        }
        Matcher beforeMatcher = RECOMMEND_MAJOR_BEFORE_PATTERN.matcher(text);
        if (beforeMatcher.find()) {
            String major = cleanMajorKeyword(beforeMatcher.group(1));
            if (major != null) {
                return major;
            }
        }
        return null;
    }

    private String extractMajorOverviewKeyword(String text) {
        Matcher matcher = MAJOR_OVERVIEW_PATTERN.matcher(text);
        while (matcher.find()) {
            String keyword = cleanMajorKeyword(matcher.group(1));
            // 疑问词不是专业方向："什么专业好就业"应引导用户给方向，而不是查询"什么"专业。
            if (keyword != null && !KEYWORD_INTERROGATIVES.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private boolean isMajorOverviewRequest(String text, String majorKeyword) {
        if (majorKeyword == null || majorKeyword.isBlank() || !containsMajorOverviewCue(text)) {
            return false;
        }
        // A request for matching, admission probability, or a school list still belongs to
        // recommendation/probability workflows even if it mentions a major name.
        return !containsAny(text, "推荐", "适合报", "能上", "录取", "概率", "院校", "学校", "志愿");
    }

    private boolean containsMajorOverviewCue(String text) {
        return containsAny(text,
                "怎么样", "好不好", "前景", "就业", "学什么", "学习内容", "课程", "介绍", "发展方向", "就业方向", "适不适合学");
    }

    /** 去掉专业名前的形容词/修饰词，如"推荐好的计算机专业"→"计算机"。 */
    private static final List<String> KEYWORD_INTERROGATIVES = List.of(
            "什么", "哪个", "啥", "哪些", "怎么样", "如何");

    private static final List<String> KEYWORD_STOPWORDS = List.of(
            "适合我的", "适合的", "合适的", "我喜欢的", "偏好的", "比较好的", "优秀的", "不错的", "好点的");

    private String cleanMajorKeyword(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim();
        for (String prefix : List.of("适合我的", "适合的", "合适的", "我喜欢的", "偏好的",
                "比较好的", "优秀的", "不错的", "好点的", "好的", "一些", "几个")) {
            if (cleaned.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length()).trim();
            }
        }
        if (cleaned.isBlank() || KEYWORD_STOPWORDS.contains(cleaned)) {
            return null;
        }
        return cleaned;
    }

    private int extractSelectionIndex(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        Matcher matcher = DIGIT_SELECTION_PATTERN.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (text.contains("第二")) {
            return 2;
        }
        if (text.contains("第三")) {
            return 3;
        }
        if (text.contains("第四")) {
            return 4;
        }
        if (text.contains("第五")) {
            return 5;
        }
        if (text.contains("第六")) {
            return 6;
        }
        return 1;
    }

    private String extractPlanName(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = SAVE_NAME_PATTERN.matcher(text);
        if (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isBlank() && !name.equals("方案")) {
                return name;
            }
        }
        return null;
    }

    private String extractSchoolName(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = SCHOOL_NAME_DETAIL_PATTERN.matcher(text);
        if (matcher.find()) {
            String matched = matcher.group(1).trim();
            for (String prefix : List.of("帮我看看", "帮我查看", "帮我查查", "看看", "查看", "查查", "介绍一下")) {
                if (matched.startsWith(prefix)) {
                    matched = matched.substring(prefix.length()).trim();
                }
            }
            return matched.isBlank() ? null : matched;
        }
        return null;
    }

    private boolean containsOrdinalReference(String text) {
        return text.contains("第一个")
                || text.contains("第二个")
                || text.contains("第三个")
                || text.contains("第四个")
                || text.contains("第五个")
                || text.contains("第六个")
                || DIGIT_SELECTION_PATTERN.matcher(text).find();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String safeContent(AgentMessage message) {
        return message.getContent() == null ? "" : message.getContent();
    }
}
