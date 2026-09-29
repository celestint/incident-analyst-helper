package com.lyl.backend.model;

import lombok.Data;

@Data
public class AnalysisReport {
    private Long id;
    private String rootCauseHypothesis;
    private Double confidence;
    private String riskLevel;
    private String recommendedActions;
    private String evidenceChain;
}
