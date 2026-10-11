package com.lyl.backend.mapper;

import com.lyl.backend.model.AnalysisReport;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 分析报告表（analysis_report）数据访问
 */
@Mapper
public interface ReportMapper {

    /**
     * 新增报告，回填自增主键
     */
    @Insert("INSERT INTO analysis_report(is_noise, needs_handling, root_cause_hypothesis, confidence, " +
            "confidence_reason, recommended_actions, judgment_logic) VALUES(#{isNoise}, #{needsHandling}, " +
            "#{rootCauseHypothesis}, #{confidence}, #{confidenceReason}, #{recommendedActions}, #{judgmentLogic})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnalysisReport report);

    /**
     * 按主键查询报告
     */
    @Select("""
            SELECT id, is_noise, needs_handling, root_cause_hypothesis, confidence,
                   confidence_reason, recommended_actions, judgment_logic, adopted, adopt_issues
            FROM analysis_report
            WHERE id = #{id}
            """)
    AnalysisReport selectById(Long id);

    /**
     * 采纳标注（评测）：adopted true=赞同 false=不赞同；adoptIssues 为不赞同原因 JSON 数组字符串（赞同/未说明时为 null）
     */
    @Update("UPDATE analysis_report SET adopted = #{adopted}, adopt_issues = #{adoptIssues} WHERE id = #{id}")
    int updateAdoption(@Param("id") Long id, @Param("adopted") Boolean adopted, @Param("adoptIssues") String adoptIssues);

    /**
     * 删除单条报告（告警级联删除用）
     */
    @Delete("DELETE FROM analysis_report WHERE id = #{id}")
    int deleteById(Long id);

    /**
     * 全部报告的采纳标注行（评测聚合用），join incident 便于按 incident 范围过滤
     */
    @Select("SELECT r.id AS reportId, r.adopted, r.adopt_issues AS adoptIssues, i.id AS incidentId " +
            "FROM analysis_report r JOIN incident i ON i.report_id = r.id")
    List<Map<String, Object>> selectAdoptionRows();
}
