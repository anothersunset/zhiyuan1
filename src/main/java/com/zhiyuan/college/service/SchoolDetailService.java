package com.zhiyuan.college.service;

import com.zhiyuan.college.mapper.UniversityMapper;
import com.zhiyuan.college.model.dto.SchoolDetailResponse;
import com.zhiyuan.college.model.dto.SchoolMajorItemResponse;
import com.zhiyuan.college.model.entity.University;
import com.zhiyuan.college.util.UniversityTagUtils;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SchoolDetailService {

    private final UniversityMapper universityMapper;
    private final JdbcTemplate jdbcTemplate;

    public SchoolDetailService(UniversityMapper universityMapper, JdbcTemplate jdbcTemplate) {
        this.universityMapper = universityMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public SchoolDetailResponse getSchoolDetail(Long universityId, String province, String subjectType) {
        University university = universityMapper.findById(universityId);
        if (university == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "school not found");
        }

        List<SchoolMajorItemResponse> majors = queryMajors(
                """
                SELECT major_name, cutoff_score, min_rank FROM (
                    SELECT COALESCE(maj.name, mac.major_name) AS major_name, mac.cutoff_score, mac.min_rank,
                           ROW_NUMBER() OVER (
                               PARTITION BY COALESCE(maj.name, mac.major_name)
                               ORDER BY mac.admission_year DESC,
                                        CASE WHEN mac.cutoff_score IS NULL THEN 1 ELSE 0 END,
                                        mac.cutoff_score DESC,
                                        CASE WHEN mac.min_rank IS NULL THEN 1 ELSE 0 END,
                                        mac.min_rank ASC
                           ) AS rn
                    FROM major_admission_cutoff mac
                    LEFT JOIN major maj ON maj.id = mac.major_id
                    WHERE mac.university_id = ?
                      AND mac.province = ?
                      AND mac.subject_type = ?
                ) ranked
                WHERE ranked.rn = 1
                ORDER BY CASE WHEN cutoff_score IS NULL THEN 1 ELSE 0 END,
                         cutoff_score DESC,
                         CASE WHEN min_rank IS NULL THEN 1 ELSE 0 END,
                         min_rank ASC,
                         major_name ASC
                """,
                universityId,
                province,
                subjectType
        );

        if (majors.isEmpty()) {
            majors = queryMajors(
                    """
                    SELECT major_name, cutoff_score, min_rank FROM (
                        SELECT COALESCE(maj.name, mac.major_name) AS major_name, mac.cutoff_score, mac.min_rank,
                               ROW_NUMBER() OVER (
                                   PARTITION BY COALESCE(maj.name, mac.major_name)
                                   ORDER BY mac.admission_year DESC,
                                            CASE WHEN mac.cutoff_score IS NULL THEN 1 ELSE 0 END,
                                            mac.cutoff_score DESC,
                                            CASE WHEN mac.min_rank IS NULL THEN 1 ELSE 0 END,
                                            mac.min_rank ASC
                               ) AS rn
                        FROM major_admission_cutoff mac
                        LEFT JOIN major maj ON maj.id = mac.major_id
                        WHERE mac.university_id = ?
                    ) ranked
                    WHERE ranked.rn = 1
                    ORDER BY CASE WHEN cutoff_score IS NULL THEN 1 ELSE 0 END,
                             cutoff_score DESC,
                             CASE WHEN min_rank IS NULL THEN 1 ELSE 0 END,
                             min_rank ASC,
                             major_name ASC
                    """,
                    universityId
            );
        }

        return new SchoolDetailResponse(
                university.getId(),
                university.getName(),
                university.getProvince(),
                university.getTier(),
                Boolean.TRUE.equals(university.getIs985()),
                Boolean.TRUE.equals(university.getIs211()),
                Boolean.TRUE.equals(university.getIsDoubleFirstClass()),
                UniversityTagUtils.buildSchoolTags(
                        university.getIs985(),
                        university.getIs211(),
                        university.getIsDoubleFirstClass(),
                        university.getTier(),
                        university.getTags()),
                university.getTags(),
                majors
        );
    }

    public SchoolDetailResponse getSchoolDetailByName(String universityName, String province, String subjectType) {
        University university = universityMapper.findByExactName(universityName == null ? null : universityName.trim());
        if (university == null) {
            // L-20260921-08：精确匹配失败后做包含匹配兜底（用户说"中南"或提取残留修饰词时，
            // 唯一命中直接解析；多个命中给出候选；零命中才报未收录）
            university = resolveByFuzzyName(universityName);
        }
        return getSchoolDetail(university.getId(), province, subjectType);
    }

    /** 包含匹配兜底：唯一命中即采用；多命中/零命中抛带候选或引导的 400。 */
    private University resolveByFuzzyName(String universityName) {
        String keyword = universityName == null ? "" : universityName.trim();
        if (keyword.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请告诉我完整的学校名称，例如：中南大学");
        }
        // 转义 LIKE 通配符，用户输入按字面匹配
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM university WHERE name LIKE CONCAT('%', ?, '%') ORDER BY id",
                Long.class,
                escaped
        );
        if (ids.size() == 1) {
            University match = universityMapper.findById(ids.get(0));
            if (match != null) {
                return match;
            }
        }
        if (ids.size() > 1) {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT name FROM university WHERE name LIKE CONCAT('%', ?, '%') ORDER BY id LIMIT 5",
                    String.class,
                    escaped
            );
            String candidates = String.join("、 ", names);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ("数据集中有 %d 所名称包含“%s”的学校：%s。请说全名。").formatted(ids.size(), keyword, candidates));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "当前数据集暂未收录该校，请换一所数据集内的学校试试");
    }

    private List<SchoolMajorItemResponse> queryMajors(String sql, Object... args) {
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchoolMajorItemResponse(
                        rs.getString("major_name"),
                        (Integer) rs.getObject("cutoff_score"),
                        (Integer) rs.getObject("min_rank")),
                args
        );
    }
}
