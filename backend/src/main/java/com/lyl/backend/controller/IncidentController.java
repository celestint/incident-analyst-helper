package com.lyl.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyl.backend.exception.ConflictException;
import com.lyl.backend.exception.ResourceNotFoundException;
import com.lyl.backend.exception.ValidationException;
import com.lyl.backend.mapper.AlertMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.mapper.ReportMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.AnalysisReport;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.ApiResponse;
import com.lyl.backend.service.AnalysisExecutor;
import com.lyl.backend.service.SsePushService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final AlertMapper alertMapper;
    private final IncidentMapper incidentMapper;
    private final ReportMapper reportMapper;
    private final AnalysisExecutor analysisExecutor;
    private final SsePushService ssePushService;
    private final ObjectMapper objectMapper;

    public IncidentController(AlertMapper alertMapper,
                             IncidentMapper incidentMapper,
                             ReportMapper reportMapper,
                             AnalysisExecutor analysisExecutor,
                             SsePushService ssePushService,
                             ObjectMapper objectMapper) {
        this.alertMapper = alertMapper;
        this.incidentMapper = incidentMapper;
        this.reportMapper = reportMapper;
        this.analysisExecutor = analysisExecutor;
        this.ssePushService = ssePushService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{alertId}/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long alertId) {
        Alert alert = alertMapper.selectByIdForUpdate(alertId);
        if (alert == null) {
            throw new ResourceNotFoundException("告警不存在");
        }

        if (alert.getIncidentId() != null) {
            // 已有关联 incident：RUNNING 中则幂等返回，已结束则 409
            Incident existing = incidentMapper.selectById(alert.getIncidentId());
            if (existing != null && "RUNNING".equals(existing.getStatus())) {
                return ApiResponse.ok(Map.of(
                        "incidentId", existing.getId(),
                        "status", existing.getStatus()
                ));
            }
            throw new ConflictException("该告警已完成分析，不可重复触发");
        }

        Incident incident = new Incident();
        incident.setAlertId(alertId);
        incident.setStatus("RUNNING");
        incident.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        incident.setReportId(null);

        incidentMapper.insert(incident);

        // 更新 alert 的 incidentId
        alertMapper.updateIncidentId(alertId, incident.getId());

        // 异步执行分析
        analysisExecutor.executeAnalysis(incident.getId());

        return ApiResponse.ok(Map.of(
                "incidentId", incident.getId(),
                "status", "RUNNING"
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getIncident(@PathVariable Long id) {
        Incident incident = incidentMapper.selectById(id);
        if (incident == null) {
            throw new ResourceNotFoundException("分析事件不存在");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("id", incident.getId());
        data.put("alertId", incident.getAlertId());
        data.put("status", incident.getStatus());
        data.put("reportId", incident.getReportId());
        data.put("createdAt", incident.getCreatedAt());
        data.put("completedAt", incident.getCompletedAt());

        // FAILED 状态返回 errorMessage
        if ("FAILED".equals(incident.getStatus()) && incident.getErrorMessage() != null) {
            data.put("errorMessage", incident.getErrorMessage());
        }

        // COMPLETED 状态加载 report
        if ("COMPLETED".equals(incident.getStatus()) && incident.getReportId() != null) {
            AnalysisReport report = reportMapper.selectById(incident.getReportId());
            if (report != null) {
                data.put("report", convertReportToResponse(report));
            }
        }

        return ApiResponse.ok(data);
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        if (incidentMapper.selectById(id) == null) {
            throw new ResourceNotFoundException("分析事件不存在");
        }

        SseEmitter emitter = new SseEmitter(60_000L);

        // 注册到推送服务，供异步分析推送事件
        ssePushService.register(id, emitter);

        return emitter;
    }

    private Map<String, Object> convertReportToResponse(AnalysisReport report) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", report.getId());
        data.put("rootCauseHypothesis", report.getRootCauseHypothesis());
        data.put("confidence", report.getConfidence());
        data.put("riskLevel", report.getRiskLevel());
        data.put("recommendedActions", parseJsonArray(report.getRecommendedActions()));
        data.put("evidenceChain", parseJsonArray(report.getEvidenceChain()));
        return data;
    }

    private Object parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
