package com.lyl.backend.model;

import lombok.Data;

/**
 * 工具幂等记录（tool_idempotency 表），防止崩溃重试导致重复副作用
 */
@Data
public class ToolIdempotency {
    /** 幂等键：SHA256(incident_id + step_seq + tool_name + args) */
    private String idempotencyKey;
    /** 所属 Incident ID */
    private Long incidentId;
    /** 该工具调用在 Incident 内的步骤序号 */
    private Integer stepSeq;
    /** 工具名称 */
    private String toolName;
    /** 工具执行结果 JSON 字符串，成功后写入，崩溃重试时直接复用 */
    private String result;
    /** 创建时间，yyyy-MM-dd HH:mm:ss */
    private String createdAt;
}
