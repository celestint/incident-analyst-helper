package com.lyl.backend.model;

import lombok.Data;

/**
 * 分析报告（analysis_report 表），结构对应 tech-design-LLM-prompt.md 的四段格式
 */
@Data
public class AnalysisReport {
    /** 主键 */
    private Long id;
    /** 事件摘要：是否噪音报警 */
    private Boolean isNoise;
    /** 事件摘要：是否需要处理 */
    private Boolean needsHandling;
    /** 当前报警产生原因，一句话结论 */
    private String rootCauseHypothesis;
    /** 结论置信度，0~1 */
    private Double confidence;
    /** 推荐SOP，JSON 数组字符串：[{"priority":1,"action":"...","risk":"LOW|MEDIUM|HIGH"}]，risk 标注该动作自身的执行风险 */
    private String recommendedActions;
    /** 判断逻辑：证据融入推理链的叙述文本（证据注明来源/数值/时间点，不单列证据表） */
    private String judgmentLogic;
}
