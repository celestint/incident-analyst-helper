package com.lyl.backend.mapper;

import com.lyl.backend.model.AnalysisReport;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReportMapper {

    @Insert("INSERT INTO analysis_report(root_cause_hypothesis, confidence, risk_level, " +
            "recommended_actions, evidence_chain) VALUES(#{rootCauseHypothesis}, #{confidence}, " +
            "#{riskLevel}, #{recommendedActions}, #{evidenceChain})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnalysisReport report);

    @Select("""
            SELECT id, root_cause_hypothesis, confidence, risk_level, recommended_actions, evidence_chain
            FROM analysis_report
            WHERE id = #{id}
            """)
    AnalysisReport selectById(Long id);
}
