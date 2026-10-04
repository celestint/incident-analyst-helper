package com.lyl.backend.service;

import com.lyl.backend.exception.ConflictException;
import com.lyl.backend.exception.ResourceNotFoundException;
import com.lyl.backend.mapper.AlertMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 分析事件（Incident）业务：start 状态机 + 事务与行锁保证并发安全。
 */
@Slf4j
@Service
public class IncidentService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlertMapper alertMapper;
    private final IncidentMapper incidentMapper;

    public IncidentService(AlertMapper alertMapper, IncidentMapper incidentMapper) {
        this.alertMapper = alertMapper;
        this.incidentMapper = incidentMapper;
    }

    /**
     * 触发分析（创建 Incident）。事务 + SELECT FOR UPDATE 锁告警行，并发触发时只允许一个创建成功。
     * - 告警不存在 → 404
     * - 已有 RUNNING 中的 incident → 幂等返回该 incident
     * - 已有 FAILED 的 incident → 重试：清错误信息、状态置回 RUNNING（seq 继续累加）
     * - 已有 COMPLETED 的 incident → 409
     */
    @Transactional
    public Incident startAnalysis(Long alertId) {
        Alert alert = alertMapper.selectByIdForUpdate(alertId);
        if (alert == null) {
            throw new ResourceNotFoundException("告警不存在");
        }

        if (alert.getIncidentId() != null) {
            Incident existing = incidentMapper.selectById(alert.getIncidentId());
            if (existing != null && IncidentStatus.RUNNING.name().equals(existing.getStatus())) {
                return existing;
            }
            if (existing != null && IncidentStatus.FAILED.name().equals(existing.getStatus())) {
                existing.setStatus(IncidentStatus.RUNNING.name());
                existing.setPhase("received");
                existing.setErrorMessage(null);
                existing.setUpdatedAt(LocalDateTime.now().format(FMT));
                incidentMapper.update(existing);
                return existing;
            }
            throw new ConflictException("该告警已完成分析，不可重复触发");
        }

        Incident incident = new Incident();
        incident.setAlertId(alertId);
        incident.setStatus(IncidentStatus.RUNNING.name());
        incident.setPhase("received");
        incident.setLastSeq(0);
        incident.setCreatedAt(LocalDateTime.now().format(FMT));
        incident.setUpdatedAt(LocalDateTime.now().format(FMT));
        incidentMapper.insert(incident);
        alertMapper.updateIncidentId(alertId, incident.getId());
        return incident;
    }

    /**
     * 断点续跑（FAILED → RUNNING）：seq/事件/幂等记录全部保留，LLM 上下文由执行器从事件重建。
     * 仅 FAILED 状态可续跑，其他状态 409。
     */
    @Transactional
    public Incident resumeAnalysis(Long incidentId) {
        Incident incident = incidentMapper.selectById(incidentId);
        if (incident == null) {
            throw new ResourceNotFoundException("分析事件不存在");
        }
        if (!IncidentStatus.FAILED.name().equals(incident.getStatus())) {
            throw new ConflictException("仅失败状态可断点续跑");
        }
        incident.setStatus(IncidentStatus.RUNNING.name());
        incident.setPhase("received");
        incident.setErrorMessage(null);
        incident.setUpdatedAt(LocalDateTime.now().format(FMT));
        incidentMapper.update(incident);
        return incident;
    }
}
