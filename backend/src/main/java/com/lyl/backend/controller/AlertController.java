package com.lyl.backend.controller;

import com.lyl.backend.exception.ResourceNotFoundException;
import com.lyl.backend.exception.ValidationException;
import com.lyl.backend.mapper.AlertMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.ApiResponse;
import com.lyl.backend.model.Incident;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertMapper alertMapper;
    private final IncidentMapper incidentMapper;

    public AlertController(AlertMapper alertMapper, IncidentMapper incidentMapper) {
        this.alertMapper = alertMapper;
        this.incidentMapper = incidentMapper;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> createAlert(@RequestBody Map<String, Object> request) {
        // 非空校验
        String alertName = (String) request.get("alertName");
        String severity = (String) request.get("severity");
        String service = (String) request.get("service");
        String startsAtStr = (String) request.get("startsAt");

        if (alertName == null || alertName.isEmpty()) {
            throw new ValidationException("alertName 不能为空");
        }
        if (severity == null || severity.isEmpty()) {
            throw new ValidationException("severity 不能为空");
        }
        if (service == null || service.isEmpty()) {
            throw new ValidationException("service 不能为空");
        }
        if (startsAtStr == null || startsAtStr.isEmpty()) {
            throw new ValidationException("startsAt 不能为空");
        }

        Alert alert = new Alert();
        alert.setAlertName(alertName);
        alert.setSeverity(severity);
        alert.setService(service);
        alert.setStartsAt(startsAtStr);
        // endsAt 可选：告警结束通知时才携带；为空（null 或空字符串，入库统一为 null）表示未结束
        String endsAtStr = (String) request.get("endsAt");
        alert.setEndsAt(endsAtStr != null && !endsAtStr.isEmpty() ? endsAtStr : null);

        // labels 可能是对象或字符串，统一转为 JSON 字符串
        Object labelsObj = request.get("labels");
        if (labelsObj instanceof Map) {
            alert.setLabels(toJson(labelsObj));
        } else {
            alert.setLabels(labelsObj != null ? labelsObj.toString() : null);
        }

        // 同一告警（服务+告警名+开始时间相同）再次 POST：携带 endsAt 时回填结束时间，否则按重复拒绝
        Alert existing = alertMapper.selectByUniqueKey(service, alertName, startsAtStr);
        if (existing != null) {
            if (endsAtStr != null && !endsAtStr.isEmpty() && !endsAtStr.equals(existing.getEndsAt())) {
                alertMapper.updateEndsAt(existing.getId(), endsAtStr);
                Map<String, Object> updated = alertData(alertMapper.selectById(existing.getId()), request.get("labels"));
                updated.put("status", "PENDING");
                updated.put("updated", true);
                return ApiResponse.ok(updated);
            }
            throw new ValidationException("该告警已存在，不能重复插入");
        }

        try {
            alertMapper.insert(alert);
        } catch (Exception e) {
            // 捕获数据库唯一键冲突异常（并发下 selectByUniqueKey 查不到的兜底）
            if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                throw new ValidationException("该告警已存在，不能重复插入");
            }
            throw e;
        }

        Map<String, Object> data = alertData(alert, request.get("labels"));
        data.put("status", "PENDING");
        return ApiResponse.ok(data);
    }

    /** 告警响应体公共字段（不含 status，由调用方按 incident 状态回填） */
    private Map<String, Object> alertData(Alert alert, Object rawLabels) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", alert.getId());
        data.put("alertName", alert.getAlertName());
        data.put("severity", alert.getSeverity());
        data.put("service", alert.getService());
        data.put("startsAt", alert.getStartsAt());
        data.put("endsAt", alert.getEndsAt());
        data.put("labels", rawLabels != null ? rawLabels : alert.getLabels());
        data.put("incidentId", null);
        return data;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listAlerts() {
        List<Alert> alerts = alertMapper.selectAll();

        // 转换为前端需要的格式
        List<Map<String, Object>> result = alerts.stream().map(alert -> {
            Map<String, Object> data = alertData(alert, null);

            // 如果有关联的 incidentId，查询 incident 状态
            if (alert.getIncidentId() != null) {
                Incident incident = incidentMapper.selectByAlertId(alert.getId());
                if (incident != null) {
                    data.put("incidentId", incident.getId());
                    data.put("status", incident.getStatus());
                }
            } else {
                data.put("status", "PENDING");
            }

            return data;
        }).toList();

        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getAlert(@PathVariable Long id) {
        Alert alert = alertMapper.selectById(id);
        if (alert == null) {
            throw new ResourceNotFoundException("告警不存在");
        }

        Map<String, Object> data = alertData(alert, null);
        data.put("status", "PENDING");

        // 如果有关联的 incident，查询 incident 状态
        Incident incident = incidentMapper.selectByAlertId(id);
        if (incident != null) {
            data.put("incidentId", incident.getId());
            data.put("status", incident.getStatus());
        }

        return ApiResponse.ok(data);
    }

    private String toJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }
}
