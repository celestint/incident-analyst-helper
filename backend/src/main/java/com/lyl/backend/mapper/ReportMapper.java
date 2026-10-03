package com.lyl.backend.mapper;

import com.lyl.backend.model.AnalysisReport;
import org.apache.ibatis.annotations.*;

/**
 * 分析报告表（analysis_report）数据访问
 */
@Mapper
public interface ReportMapper {

    /**
     * 新增报告，回填自增主键
     */
    @Insert("INSERT INTO analysis_report(is_noise, needs_handling, root_cause_hypothesis, confidence, " +
            "recommended_actions, judgment_logic) VALUES(#{isNoise}, #{needsHandling}, #{rootCauseHypothesis}, " +
            "#{confidence}, #{recommendedActions}, #{judgmentLogic})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnalysisReport report);

    /**
     * 按主键查询报告
     */
    @Select("""
            SELECT id, is_noise, needs_handling, root_cause_hypothesis, confidence,
                   recommended_actions, judgment_logic
            FROM analysis_report
            WHERE id = #{id}
            """)
    AnalysisReport selectById(Long id);
}
