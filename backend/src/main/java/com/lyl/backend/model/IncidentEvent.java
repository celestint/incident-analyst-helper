package com.lyl.backend.model;

import lombok.Data;

/**
 * Incident 过程事件（incident_events 表），只追加不修改，事件溯源核心表
 */
@Data
public class IncidentEvent {
    /** 自增主键 */
    private Long id;
    /** 所属 Incident ID */
    private Long incidentId;
    /** Incident 内事件序号，从 1 开始递增，UNIQUE(incident_id, seq)，前端按此增量拉取 */
    private Integer seq;
    /**
     * 事件类型，共 7 类：
     * incident_received   开始分析（payload: alertName/service/severity/startsAt）
     * agent_thought       模型本轮思考/观察结论摘要（payload: thought）
     * tool_call_start     工具开始执行（payload: tool/args/stepSeq）
     * tool_call_result    工具执行结果（payload: tool/success/summary）
     * evidence_collected  采集到的证据（payload: source/content/timestamp）
     * report_finalized    报告定稿（payload: reportId）
     * error               过程错误（payload: message）
     */
    private String eventType;
    /** 事件负载 JSON 字符串，结构随 eventType 变化 */
    private String payload;
    /** 事件发生时间，yyyy-MM-dd HH:mm:ss */
    private String createdAt;
}
