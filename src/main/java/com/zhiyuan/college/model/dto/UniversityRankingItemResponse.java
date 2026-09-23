package com.zhiyuan.college.model.dto;

import java.util.List;

/** 院校排行榜单条：数据来源为软科中国大学排名（soft_ranking，可溯源），非本地生成。 */
public record UniversityRankingItemResponse(Long id,
                                            String name,
                                            String province,
                                            String tier,
                                            String nature,
                                            String schoolType,
                                            Boolean is985,
                                            Boolean is211,
                                            Boolean isDoubleFirstClass,
                                            List<String> schoolTags,
                                            String tags,
                                            Integer softRanking) {
}
