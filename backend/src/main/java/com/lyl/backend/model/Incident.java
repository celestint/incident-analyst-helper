package com.lyl.backend.model;

import lombok.Data;

/**
 * 分析事件（incident 表）
 */
@Data
public class Incident {
    /** 主键 */
    private Long id;
    /** 关联告警 ID（alert.id） */
    private Long alertId;
    /** 粗粒度状态：PENDING/RUNNING/COMPLETED/FAILED */
    private String status;
    /** 细粒度阶段：received/thinking/tool_running/finalizing/done，仅 RUNNING 有意义 */
    private String phase;
    /** 已写入的最大事件序号（incident_events.seq），前端增量拉取游标 */
    private Integer lastSeq;
    /** 关联报告 ID（analysis_report.id），分析完成后才有值 */
    private Long reportId;
    /** 创建时间，yyyy-MM-dd HH:mm:ss */
    private String createdAt;
    /** 最后更新时间，崩溃恢复据此判断 RUNNING 是否超时 */
    private String updatedAt;
    /** 完成时间，yyyy-MM-dd HH:mm:ss，仅 COMPLETED 有值 */
    private String completedAt;
    /** 失败原因，仅 FAILED 有值 */
    private String errorMessage;
}
