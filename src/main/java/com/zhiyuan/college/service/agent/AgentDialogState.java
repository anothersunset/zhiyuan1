package com.zhiyuan.college.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyuan.college.model.entity.AgentMessage;
import com.zhiyuan.college.model.entity.UserAccount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单轮决策的统一对话状态（阶段②：把原先散落在 AgentDecisionService 的 5 处
 * recentMessages 反向扫描合并为 {@link #build} 里的<b>单次</b>遍历，另加消息级
 * 派生（正则提取），构建一次、本地路由 / 守护闸门 / LLM 提示词三处共用。
 *
 * <p><b>语义保留纪律</b>：合并前各扫描的"首个命中即停"与信任边界各不相同，统一
 * 遍历逐条等价保留——
 * <ul>
 *   <li>学校续指槽：byName 工具消息按"载荷 universityName"命中；文本扫描只信
 *       USER 消息；同一消息先查载荷再查文本，首个命中即停；</li>
 *   <li>最近一轮推荐快照：首个"推荐类 TOOL_RESULT 且载荷非空"的消息决定计数，
 *       解析失败也停（保持不可用），不再向更早消息回溯；</li>
 *   <li>志愿单快照：首个"计划类 TOOL_RESULT"（不要求载荷非空）的内容，截断 60 字；</li>
 *   <li>删除确认槽：只认最后一条助手文本消息及其紧邻前一条消息的角色与内容；</li>
 *   <li>方向填槽：只认最后一条助手文本消息是否为追问模板。</li>
 * </ul>
 */
final class AgentDialogState {

    private static final Pattern RECOMMEND_MAJOR_AFTER_PATTERN = Pattern.compile("推荐(?:一下|几个|一些)?([\\p{IsHan}A-Za-z0-9]{2,12})(?:专业|方向)");
    private static final Pattern RECOMMEND_MAJOR_BEFORE_PATTERN = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,12})(?:专业|方向).{0,8}推荐");
    private static final Pattern DIGIT_SELECTION_PATTERN = Pattern.compile("第\\s*(\\d{1,2})\\s*(?:个|所|条|项)");
    private static final Pattern SAVE_NAME_PATTERN = Pattern.compile("保存(?:为|成)?[《\u201c\\\"]?([^》\u201d\\n]{2,30})[》\u201d\\\"]?(?:方案)?");
    private static final Pattern SCHOOL_NAME_DETAIL_PATTERN = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,20}(?:大学|学院|学校))");
    private static final Pattern MAJOR_OVERVIEW_PATTERN = Pattern.compile(
            "(?:^|[，。！？?]|想了解|了解一下|请介绍|介绍一下|关于|问问|看看|查一下|帮我看看|帮我介绍)([\\p{IsHan}A-Za-z0-9]{2,16})(?:专业|方向)"
    );

    /** 单条消息在提示词里的内容截断长度（防长 markdown 撑爆 token）。 */
    static final int MAX_RENDERED_CONTENT_CHARS = 240;

    private final AgentIntentLexicon lexicon;
    private final ObjectMapper objectMapper;
    private final List<AgentMessage> recentMessages;

    // ----- 消息级派生（正则/词典，作用于 normalized message） -----
    private final String normalizedMessage;
    private final boolean ordinalReference;
    private final int selectionIndex;
    private final String planName;
    private final String schoolName;
    private final String strippedSchoolName;
    private final String majorKeyword;
    private final String majorOverviewKeyword;
    private final boolean overviewCue;

    // ----- 窗口级槽位（单次反向遍历，首中即停） -----
    private final String lastMentionedSchool;
    private final boolean pendingMajorDirection;
    private final String lastAssistantText;
    private final boolean hasMessageBeforeLastAssistant;
    private final String roleBeforeLastAssistant;
    private final String contentBeforeLastAssistant;
    private final int lastRecommendationCount;
    private final String planHint;

    // ----- 用户画像快照 -----
    private final boolean profileComplete;
    private final String profileLine;
    private final Map<String, Object> profileMap;

    private AgentDialogState(Builder builder) {
        this.lexicon = builder.lexicon;
        this.objectMapper = builder.objectMapper;
        this.recentMessages = builder.recentMessages;
        this.normalizedMessage = builder.normalizedMessage;
        this.ordinalReference = builder.ordinalReference;
        this.selectionIndex = builder.selectionIndex;
        this.planName = builder.planName;
        this.schoolName = builder.schoolName;
        this.strippedSchoolName = builder.strippedSchoolName;
        this.majorKeyword = builder.majorKeyword;
        this.majorOverviewKeyword = builder.majorOverviewKeyword;
        this.overviewCue = builder.overviewCue;
        this.lastMentionedSchool = builder.lastMentionedSchool;
        this.pendingMajorDirection = builder.pendingMajorDirection;
        this.lastAssistantText = builder.lastAssistantText;
        this.hasMessageBeforeLastAssistant = builder.hasMessageBeforeLastAssistant;
        this.roleBeforeLastAssistant = builder.roleBeforeLastAssistant;
        this.contentBeforeLastAssistant = builder.contentBeforeLastAssistant;
        this.lastRecommendationCount = builder.lastRecommendationCount;
        this.planHint = builder.planHint;
        this.profileComplete = builder.profileComplete;
        this.profileLine = builder.profileLine;
        this.profileMap = builder.profileMap;
    }

    /** 单次构建：消息级派生 + 一遍反向扫描同时收集全部窗口槽位。 */
    static AgentDialogState build(String userMessage,
                                  List<AgentMessage> recentMessages,
                                  UserAccount user,
                                  AgentIntentLexicon lexicon,
                                  ObjectMapper objectMapper) {
        return new Builder(userMessage, recentMessages, user, lexicon, objectMapper).build();
    }

    private static final class Builder {
        private final AgentIntentLexicon lexicon;
        private final ObjectMapper objectMapper;
        private final List<AgentMessage> recentMessages;
        private final UserAccount user;
        private final String normalizedMessage;

        private boolean ordinalReference;
        private int selectionIndex = 1;
        private String planName;
        private String schoolName;
        private String strippedSchoolName;
        private String majorKeyword;
        private String majorOverviewKeyword;
        private boolean overviewCue;

        private String lastMentionedSchool;
        private boolean pendingMajorDirection;
        private String lastAssistantText;
        private boolean hasMessageBeforeLastAssistant;
        private String roleBeforeLastAssistant;
        private String contentBeforeLastAssistant;
        private int lastRecommendationCount = -1;
        private String planHint;

        private boolean profileComplete;
        private String profileLine;
        private Map<String, Object> profileMap;

        private Builder(String userMessage, List<AgentMessage> recentMessages,
                        UserAccount user, AgentIntentLexicon lexicon, ObjectMapper objectMapper) {
            this.lexicon = lexicon;
            this.objectMapper = objectMapper;
            this.recentMessages = recentMessages == null ? List.of() : recentMessages;
            this.user = user;
            this.normalizedMessage = userMessage == null ? "" : userMessage.trim();
        }

        private AgentDialogState build() {
            deriveFromMessage();
            scanWindowOnce();
            deriveProfile();
            return new AgentDialogState(this);
        }

        /** 消息级派生：全部正则/词典提取只对 normalized message 跑一次。 */
        private void deriveFromMessage() {
            ordinalReference = containsOrdinalReference(normalizedMessage);
            selectionIndex = extractSelectionIndex(normalizedMessage);
            planName = extractPlanName(normalizedMessage);
            schoolName = extractSchoolName(normalizedMessage);
            overviewCue = containsMajorOverviewCue(normalizedMessage);
            majorKeyword = extractMajorKeyword(normalizedMessage);
            if (majorKeyword == null && overviewCue) {
                majorOverviewKeyword = extractMajorOverviewKeyword(normalizedMessage);
            }
            // 校名提取前剥离前导动词，避免正则从"推"起步吃出"推荐湘潭大学"
            strippedSchoolName = extractLongestSchoolName(stripSchoolVerbs(normalizedMessage));
        }

        /**
         * 窗口级槽位的单次反向遍历：每条消息上并行评估五类槽位的谓词，
         * 各槽位独立"首个命中即停"（与合并前各扫描的停止规则逐条等价）。
         */
        private void scanWindowOnce() {
            boolean schoolSlotSet = false;
            boolean recommendationScanned = false;
            boolean planHintScanned = false;
            boolean assistantTextFound = false;

            for (int i = recentMessages.size() - 1; i >= 0; i--) {
                AgentMessage message = recentMessages.get(i);
                String content = message.getContent() == null ? "" : message.getContent();

                // --- 学校续指槽：同一条消息先查 byName 载荷，再查 USER 文本（首中即停） ---
                if (!schoolSlotSet
                        && AgentToolNames.GET_SCHOOL_DETAIL_BY_NAME.equals(message.getToolName())
                        && message.getPayloadJson() != null && !message.getPayloadJson().isBlank()) {
                    String name = extractUniversityNameFromPayload(message.getPayloadJson());
                    if (!name.isBlank()) {
                        lastMentionedSchool = name;
                        schoolSlotSet = true;
                    }
                }
                if (!schoolSlotSet && AgentRoles.USER.equals(message.getRole())) {
                    String schoolNameInText = extractLongestSchoolName(content);
                    if (schoolNameInText != null) {
                        lastMentionedSchool = schoolNameInText;
                        schoolSlotSet = true;
                    }
                }

                // --- 最近一轮推荐快照：首个推荐类 TOOL_RESULT（载荷非空）决定计数，之后不再回溯 ---
                if (!recommendationScanned
                        && AgentMessageTypes.TOOL_RESULT.equals(message.getMessageType())
                        && (AgentToolNames.RECOMMEND_SCHOOLS.equals(message.getToolName())
                            || AgentToolNames.RECOMMEND_MAJORS.equals(message.getToolName()))
                        && message.getPayloadJson() != null && !message.getPayloadJson().isBlank()) {
                    recommendationScanned = true;
                    try {
                        JsonNode topItems = objectMapper.readTree(message.getPayloadJson()).path("topItems");
                        if (topItems.isArray()) {
                            lastRecommendationCount = topItems.size();
                        }
                    } catch (Exception ignored) {
                        // 解析失败保持不可用（与合并前行为一致：不再回溯更早消息）
                    }
                }

                // --- 志愿单快照：首个计划类 TOOL_RESULT（不要求载荷非空）---
                if (!planHintScanned
                        && AgentMessageTypes.TOOL_RESULT.equals(message.getMessageType())
                        && (AgentToolNames.GET_CURRENT_PLAN.equals(message.getToolName())
                            || AgentToolNames.ADD_PLAN_ITEM.equals(message.getToolName())
                            || AgentToolNames.REMOVE_PLAN_ITEM.equals(message.getToolName()))) {
                    planHintScanned = true;
                    planHint = content.isBlank() ? "暂无线索"
                            : content.length() > 60 ? content.substring(0, 60) + "…" : content;
                }

                // --- 最后一条助手文本 + 其紧邻前一条（删除确认槽 / 方向填槽的信任边界） ---
                if (!assistantTextFound
                        && AgentRoles.ASSISTANT.equals(message.getRole())
                        && AgentMessageTypes.TEXT.equals(message.getMessageType())) {
                    assistantTextFound = true;
                    lastAssistantText = content;
                    if (i >= 1) {
                        AgentMessage previous = recentMessages.get(i - 1);
                        hasMessageBeforeLastAssistant = true;
                        roleBeforeLastAssistant = previous.getRole();
                        contentBeforeLastAssistant = previous.getContent() == null ? "" : previous.getContent();
                    }
                }

                // 全部槽位就绪可提前收束
                if (schoolSlotSet && recommendationScanned && planHintScanned && assistantTextFound) {
                    break;
                }
            }

            pendingMajorDirection = lastAssistantText != null
                    && lastAssistantText.contains("告诉我方向") && lastAssistantText.contains("专业推荐");
        }

        private void deriveProfile() {
            profileComplete = user != null && user.getScore() != null
                    && user.getSubjectType() != null
                    && user.getExamProvince() != null && !user.getExamProvince().isBlank();
            profileLine = profileComplete
                    ? "%s/%s/%s分（完整，可直接推荐）".formatted(
                            user.getExamProvince(), user.getSubjectType().getDisplayName(), user.getScore())
                    : "不完整（调用推荐类工具前需先引导完善）";
            // 提示词里的画像明细（与合并前 buildUserPrompt 逐字一致）：画像不完整时
            // LLM 仍需要看到已填的字段值（如只缺省份但分数已知）。
            LinkedHashMap<String, Object> profile = new LinkedHashMap<>();
            profile.put("userId", user == null ? null : user.getId());
            profile.put("username", user == null ? null : user.getUsername());
            profile.put("score", user == null ? null : user.getScore());
            profile.put("subjectType", user == null || user.getSubjectType() == null ? null : user.getSubjectType().getDisplayName());
            profile.put("examProvince", user == null ? null : user.getExamProvince());
            profileMap = profile;
        }

        // ----- 消息级提取助手（自 AgentDecisionService 原样迁移，词表驱动） -----

        private String extractMajorKeyword(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            // 先精确匹配常见专业关键词：避免"推荐好的计算机专业"被正则捕获成"好的计算机"。
            // 按「最长命中优先」匹配："软件工程就业前景"应命中"软件工程"而非其子串"软件"。
            String best = null;
            for (String keyword : lexicon.majorKeywords()) {
                if (text.contains(keyword) && (best == null || keyword.length() > best.length())) {
                    best = keyword;
                }
            }
            if (best != null) {
                return best;
            }
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
                if (keyword != null && !lexicon.keywordInterrogatives().contains(keyword)) {
                    return keyword;
                }
            }
            return null;
        }

        private boolean containsMajorOverviewCue(String text) {
            return lexicon.containsAny(text, "majorOverviewCues");
        }

        private String cleanMajorKeyword(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String cleaned = value.trim();
            // 剥离修饰前缀（顺序敏感组 majorKeywordPrefixes，与原列表顺序逐字一致）；
            // 与原实现一致：不 break，单次调用可连续剥离多个前缀（如"适合我的好的X"）。
            for (String prefix : lexicon.majorKeywordPrefixes()) {
                if (cleaned.startsWith(prefix)) {
                    cleaned = cleaned.substring(prefix.length()).trim();
                }
            }
            if (cleaned.isBlank() || lexicon.keywordStopwords().contains(cleaned)) {
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
            String matched = null;
            // 取**最长**匹配：优先命中真实校名（湘潭大学），而不是"推荐学校/按学校"这类动词+泛称组合
            while (matcher.find()) {
                String found = matcher.group(1).trim();
                if (matched == null || found.length() > matched.length()) {
                    matched = found;
                }
            }
            if (matched == null) {
                return null;
            }
            // 剥离一个查询动词前缀（顺序敏感组 schoolNamePrefixes，与原列表顺序逐字一致）
            for (String prefix : lexicon.group("schoolNamePrefixes")) {
                if (matched.startsWith(prefix)) {
                    matched = matched.substring(prefix.length()).trim();
                }
            }
            return matched.isBlank() || !isPlausibleSchoolName(matched) ? null : matched;
        }

        /** 剥离校名前的前导动词（词序来自顺序敏感组 schoolStripVerbs：与原 replace 链逐字等价）。 */
        private String stripSchoolVerbs(String text) {
            String stripped = text;
            for (String verb : lexicon.group("schoolStripVerbs")) {
                stripped = stripped.replace(verb, "▌");
            }
            return stripped;
        }

        private boolean isPlausibleSchoolName(String name) {
            if (name == null || name.isBlank()) {
                return false;
            }
            String stem = name.replaceAll("(大学|学院|学校)$", "").trim();
            if (stem.length() < 2) {
                return false;
            }
            if (stem.contains("推荐") || stem.contains("报") || stem.contains("选")
                    || stem.contains("几所") || stem.contains("一所")) {
                return false;
            }
            return !stem.startsWith("这") && !stem.startsWith("那") && !stem.startsWith("该")
                    && !stem.startsWith("某") && !stem.startsWith("按")
                    && !stem.startsWith("去") && !stem.startsWith("上") && !stem.startsWith("想")
                    && !stem.startsWith("哪") && !stem.startsWith("什")
                    && !stem.startsWith("几") && !stem.startsWith("类");
        }

        /** 取文本中**最长**的校名匹配："已按学校名查询 湖南师范大学 的详情"应得湖南师范大学，而非"已按学校"。 */
        private String extractLongestSchoolName(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            Matcher matcher = SCHOOL_NAME_DETAIL_PATTERN.matcher(text);
            String best = null;
            while (matcher.find()) {
                String found = matcher.group(1).trim();
                if (isPlausibleSchoolName(found) && (best == null || found.length() > best.length())) {
                    best = found;
                }
            }
            return best;
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

        private String extractUniversityNameFromPayload(String payloadJson) {
            try {
                return objectMapper.readTree(payloadJson).path("universityName").asText("");
            } catch (Exception ex) {
                return "";
            }
        }
    }

    // ---------- 供路由 / 闸门 / 提示词消费的只读接口 ----------

    String normalizedMessage() {
        return normalizedMessage;
    }

    List<AgentMessage> recentMessages() {
        return recentMessages;
    }

    boolean ordinalReference() {
        return ordinalReference;
    }

    int selectionIndex() {
        return selectionIndex;
    }

    String planName() {
        return planName;
    }

    String schoolName() {
        return schoolName;
    }

    String strippedSchoolName() {
        return strippedSchoolName;
    }

    /** 基础提取（词典 + 推荐正则）；不含概览兜底与目录兜底。 */
    String majorKeyword() {
        return majorKeyword;
    }

    /** 概览句式兜底提取（仅在存在概览线索时已计算，可能为 null）。 */
    String majorOverviewKeyword() {
        return majorOverviewKeyword;
    }

    boolean overviewCue() {
        return overviewCue;
    }

    String lastMentionedSchool() {
        return lastMentionedSchool;
    }

    boolean pendingMajorDirectionSlot() {
        return pendingMajorDirection;
    }

    /**
     * 删除确认槽：只认最后一条助手文本消息是否为"确认删除第N个"提示，
     * 且其紧邻前一条是包含删除动词与方案名词的用户消息（与合并前语义逐字等价）。
     *
     * <p>匹配前对助手文本做空白归一化：LLM 兜底生成的确认话术可能是"确认删除第 1 个"
     * （带空格），用户照抄回复后本地检测若按无空格字面匹配会断链——
     * 用户按提示回复却被告知"没有检测到待确认请求"（L-20260922 AI 对话多轮测试发现）。</p>
     */
    boolean hasPendingDeleteConfirmation(int index) {
        if (lastAssistantText == null) {
            return false;
        }
        String normalizedAssistant = lastAssistantText.replaceAll("\\s+", "");
        if (!normalizedAssistant.contains("确认删除第" + index + "个")) {
            return false;
        }
        if (!hasMessageBeforeLastAssistant || !AgentRoles.USER.equals(roleBeforeLastAssistant)) {
            return false;
        }
        // L-20260922 多轮测试：用户删除请求可能不带"志愿/方案"名词（"删除第1个"），
        // 只要求前置用户消息含删除动词即可——助手确认提示本身已是"存在待确认删除"的凭证。
        return lexicon.containsAny(contentBeforeLastAssistant, "deleteVerbs");
    }

    /** 最近一轮推荐可用项数；-1 表示不可用（不能引用"第 N 个"）。 */
    int lastRecommendationCount() {
        return lastRecommendationCount;
    }

    String planHint() {
        return planHint;
    }

    boolean profileComplete() {
        return profileComplete;
    }

    /** 用户画像快照行（完整时含省份/科类/分数）。 */
    String profileLine() {
        return profileLine;
    }

    /** 提示词用的画像明细 Map（与合并前格式一致，含不完整画像的已填字段）。 */
    Map<String, Object> profileMap() {
        return profileMap;
    }

    /** 系统实时状态快照文本（供 LLM 提示词注入，与合并前输出逐字一致）。 */
    String snapshotText() {
        String recommendationStatus = lastRecommendationCount < 0
                ? "不可用（需先推荐才能引用“第 N 个”或加入志愿单）"
                : "可用（共 %d 项，可用“第 N 个”引用或加入志愿单）".formatted(lastRecommendationCount);
        return "- 用户画像：" + profileLine
                + "\n- 最近一轮推荐：" + recommendationStatus
                + "\n- 志愿单最近状态：" + (planHint == null ? "暂无线索（可调用 getCurrentPlan 查询）" : planHint);
    }

    /** 单条消息在提示词里的内容截断长度。 */
    static int maxRenderedContentChars() {
        return MAX_RENDERED_CONTENT_CHARS;
    }
}
