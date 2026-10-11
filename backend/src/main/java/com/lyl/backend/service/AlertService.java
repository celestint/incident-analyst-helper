package com.lyl.backend.service;

import com.lyl.backend.exception.ResourceNotFoundException;
import com.lyl.backend.exception.ValidationException;
import com.lyl.backend.mapper.AlertMapper;
import com.lyl.backend.mapper.IncidentEventMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.mapper.ReportMapper;
import com.lyl.backend.mapper.ToolIdempotencyMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.Incident;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 告警批量操作（评测打标与删除）。
 * 打标：三布尔（isProd/isEval/isTest）完整状态批量更新，三档独立可组合，正式=isProd。
 * 删除：级联清理告警关联的全部分析数据（incident / incident_events / tool_idempotency / analysis_report），
 * 事务保证不留孤儿数据。
 */
@Slf4j
@Service
public class AlertService {

    private final AlertMapper alertMapper;
    private final IncidentMapper incidentMapper;
    private final IncidentEventMapper incidentEventMapper;
    private final ToolIdempotencyMapper toolIdempotencyMapper;
    private final ReportMapper reportMapper;

    public AlertService(AlertMapper alertMapper,
                        IncidentMapper incidentMapper,
                        IncidentEventMapper incidentEventMapper,
                        ToolIdempotencyMapper toolIdempotencyMapper,
                        ReportMapper reportMapper) {
        this.alertMapper = alertMapper;
        this.incidentMapper = incidentMapper;
        this.incidentEventMapper = incidentEventMapper;
        this.toolIdempotencyMapper = toolIdempotencyMapper;
        this.reportMapper = reportMapper;
    }

    /**
     * 批量打标：按提交的三布尔完整状态更新（无互斥，正式=isProd=1）
     */
    @Transactional
    public int mark(List<Long> ids, Boolean isProd, Boolean isEval, Boolean isTest) {
        requireIds(ids);
        if (isProd == null || isEval == null || isTest == null) {
            throw new ValidationException("isProd/isEval/isTest 必须为布尔值");
        }
        int updated = 0;
        for (Long id : ids) {
            if (alertMapper.selectById(id) == null) {
                throw new ResourceNotFoundException("告警不存在: " + id);
            }
            updated += alertMapper.updateFlags(id, isProd, isEval, isTest);
        }
        return updated;
    }

    /**
     * 批量删除告警：级联删除关联 incident / 事件流 / 工具幂等记录 / 分析报告，同一事务
     */
    @Transactional
    public int delete(List<Long> ids) {
        requireIds(ids);
        int deleted = 0;
        for (Long id : ids) {
            Alert alert = alertMapper.selectById(id);
            if (alert == null) {
                throw new ResourceNotFoundException("告警不存在: " + id);
            }
            Incident incident = incidentMapper.selectByAlertId(id);
            if (incident != null) {
                // 顺序：先删子表（事件/幂等）与 incident，最后删 report——
                // incident.report_id 外键指向 analysis_report，反之会约束冲突
                incidentEventMapper.deleteByIncidentId(incident.getId());
                toolIdempotencyMapper.deleteByIncidentId(incident.getId());
                incidentMapper.deleteById(incident.getId());
                if (incident.getReportId() != null) {
                    reportMapper.deleteById(incident.getReportId());
                }
            }
            deleted += alertMapper.deleteById(id);
        }
        log.info("Batch deleted {} alert(s) with cascaded analysis data", deleted);
        return deleted;
    }

    private void requireIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ValidationException("ids 不能为空");
        }
    }
}
