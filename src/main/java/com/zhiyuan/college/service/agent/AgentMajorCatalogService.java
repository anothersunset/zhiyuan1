package com.zhiyuan.college.service.agent;

import com.zhiyuan.college.mapper.MajorMapper;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 专业目录服务（阶段③）：major 表是 100 行级 curated 目录，启动后全量缓存在内存，
 * 为 LLM 与本地两条关键词路径提供统一的归一化与兜底。
 *
 * <p>设计约束（评审定稿）：
 * <ul>
 *   <li>决策路径零同步建表成本——目录常驻内存，TTL 刷新让管理端改动 5 分钟内生效；</li>
 *   <li>归一化<b>绝不硬拒绝</b>：无法解析时原样放行，保住推荐引擎自身的优雅降级
 *       （0 结果 fallback 比参数报错对用户更友好）；</li>
 *   <li>模糊匹配只在<b>唯一命中</b>时改写关键词，含糊一律保持原样，防止把
 *       "靠谱""好的"之类修饰词糊弄成某个专业。</li>
 * </ul>
 */
@Service
public class AgentMajorCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentMajorCatalogService.class);

    /** 目录刷新间隔：管理端新增/改名专业后最迟 5 分钟生效，无需重启。 */
    private static final long REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000L;
    /** 加载失败后的重试间隔，避免每次决策都打一次失效查询。 */
    private static final long FAILURE_RETRY_MILLIS = 60 * 1000L;
    /** 参与模糊匹配的最小关键词长度（单字包含匹配噪声过大）。 */
    private static final int MIN_FUZZY_LENGTH = 2;

    private final MajorMapper majorMapper;
    private final long refreshIntervalMillis;
    private final long failureRetryMillis;
    private volatile List<String> cachedNames = List.of();
    private volatile long lastLoadedAtMillis = 0;

    @Autowired
    public AgentMajorCatalogService(MajorMapper majorMapper) {
        this(majorMapper, REFRESH_INTERVAL_MILLIS, FAILURE_RETRY_MILLIS);
    }

    /** 测试友好：可注入更短的刷新/重试间隔。 */
    AgentMajorCatalogService(MajorMapper majorMapper, long refreshIntervalMillis, long failureRetryMillis) {
        this.majorMapper = majorMapper;
        this.refreshIntervalMillis = refreshIntervalMillis;
        this.failureRetryMillis = failureRetryMillis;
    }

    /**
     * 把关键词归一化到目录标准名：
     * <ol>
     *   <li>精确命中（忽略大小写）→ 标准名；</li>
     *   <li>目录名包含关键词且唯一（"考古" → "考古学"）→ 标准名；</li>
     *   <li>关键词包含某个目录名（脏提取"想学考古类的" ⊃ "考古学"）→ 取最长命中，
     *       并列最长视为含糊，保持原样。</li>
     * </ol>
     * 无法解析返回 {@link Optional#empty()}，调用方应原样使用关键词。
     */
    public Optional<String> resolve(String majorKeyword) {
        if (majorKeyword == null) {
            return Optional.empty();
        }
        String keyword = majorKeyword.trim();
        if (keyword.length() < MIN_FUZZY_LENGTH) {
            return Optional.empty();
        }
        List<String> names = names();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        for (String name : names) {
            if (name.equalsIgnoreCase(keyword)) {
                return Optional.of(name);
            }
        }
        List<String> containing = names.stream()
                .filter(name -> name.contains(keyword))
                .toList();
        if (containing.size() == 1) {
            return Optional.of(containing.get(0));
        }
        List<String> wrapped = names.stream()
                .filter(name -> keyword.contains(name) && name.length() >= MIN_FUZZY_LENGTH)
                .toList();
        if (wrapped.size() == 1) {
            return Optional.of(wrapped.get(0));
        }
        if (wrapped.size() > 1) {
            List<String> byLengthDesc = wrapped.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
            if (byLengthDesc.get(0).length() > byLengthDesc.get(1).length()) {
                return Optional.of(byLengthDesc.get(0));
            }
        }
        return Optional.empty();
    }

    /**
     * 在自由文本里找目录内专业名（决策层兜底：正则与词典都提取失败时调用）。
     * 只在最长命中唯一时返回，避免"大数据/数据科学"并存时的误选。
     */
    public Optional<String> findInText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<String> hits = names().stream()
                .filter(name -> name.length() >= MIN_FUZZY_LENGTH && text.contains(name))
                .toList();
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        int longest = hits.stream().mapToInt(String::length).max().orElse(0);
        List<String> longestHits = hits.stream()
                .filter(name -> name.length() == longest)
                .toList();
        return longestHits.size() == 1 ? Optional.of(longestHits.get(0)) : Optional.empty();
    }

    /** 目录标准名集合（只读）；TTL 过期或加载失败时尝试重载，失败沿用旧缓存并退避重试。 */
    private List<String> names() {
        long now = System.currentTimeMillis();
        boolean loadFailed = lastLoadedAtMillis > 0 && cachedNames.isEmpty();
        if (!cachedNames.isEmpty() && now - lastLoadedAtMillis < refreshIntervalMillis) {
            return cachedNames;
        }
        if (loadFailed && now - lastLoadedAtMillis < failureRetryMillis) {
            return cachedNames;
        }
        synchronized (this) {
            if (!cachedNames.isEmpty() && System.currentTimeMillis() - lastLoadedAtMillis < refreshIntervalMillis) {
                return cachedNames;
            }
            try {
                Set<String> distinct = new LinkedHashSet<>();
                for (var major : majorMapper.findAllOrdered()) {
                    if (major.getName() != null && !major.getName().isBlank()) {
                        distinct.add(major.getName().trim());
                    }
                }
                cachedNames = List.copyOf(distinct);
                lastLoadedAtMillis = System.currentTimeMillis();
                log.info("Agent major catalog loaded: {} distinct names", cachedNames.size());
            } catch (Exception ex) {
                lastLoadedAtMillis = System.currentTimeMillis();
                log.warn("Agent major catalog load failed, degrade to passthrough: {}", ex.getMessage());
            }
            return cachedNames;
        }
    }
}
