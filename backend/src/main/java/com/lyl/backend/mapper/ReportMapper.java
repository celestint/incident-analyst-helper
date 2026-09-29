package com.lyl.backend.mapper;

import com.lyl.backend.model.AnalysisReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReportMapper {

    @Select("""
            SELECT id, root_cause_hypothesis, confidence, risk_level, recommended_actions, evidence_chain
            FROM analysis_report
            WHERE id = #{id}
            """)
    AnalysisReport selectById(Long id);
}
