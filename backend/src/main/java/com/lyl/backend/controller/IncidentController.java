package com.lyl.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyl.backend.exception.ResourceNotFoundException;
import com.lyl.backend.exception.ValidationException;
import com.lyl.backend.mapper.IncidentEventMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.mapper.ReportMapper;
import com.lyl.backend.model.AnalysisReport;
import com.lyl.backend.model.ApiResponse;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentEvent;
import com.lyl.backend.service.AnalysisDispatcher;
import com.lyl.backend.service.IncidentEventService;
import com.lyl.backend.service.IncidentService;
import com.lyl.backend.service.RecommendedActionsNormalizer;
import com.lyl.backend.service.SsePushService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    /** 不赞同原因的合法取值（评测标注） */
    private static final Set<String> ALLOWED_ADOPT_ISSUES = Set.of("noise", "evidence", "sop", "logic", "leak", "redundant");

    private final IncidentService incidentService;
    private final AnalysisDispatcher analysisDispatcher;
    private final IncidentMapper incidentMapper;
    private final IncidentEventMapper incidentEventMapper;
    private final IncidentEventService incidentEventService;
    private final ReportMapper reportMapper;
    private final SsePushService ssePushService;
    private final ObjectMapper objectMapper;

    public IncidentController(IncidentService incidentService,
                              AnalysisDispatcher analysisDispatcher,
                              IncidentMapper incidentMapper,
                              IncidentEventMapper incidentEventMapper,
                              IncidentEventService incidentEventService,
                              ReportMapper reportMapper,
                              SsePushService ssePushService,
                              ObjectMapper objectMapper) {
        this.incidentService = incidentService;
        this.analysisDispatcher = analysisDispatcher;
        this.incidentMapper = incidentMapper;
        this.incidentEventMapper = incidentEventMapper;
        this.incidentEventService = incidentEventService;
        this.reportMapper = reportMapper;
        this.ssePushService = ssePushService;
        this.objectMapper = objectMapper;
    }

    /**
     * 触发分析：创建/复用 Incident（事务 + 行锁），提交后异步执行分析。
     * FAILED 状态下调用即"重新分析"（从头再来，全新 LLM 上下文）。
     */
    @PostMapping("/{alertId}/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long alertId) {
        Incident incident = incidentService.startAnalysis(alertId);
        analysisDispatcher.dispatch(incident.getId());
        return ApiResponse.ok(Map.of(
                "incidentId", incident.getId(),
                "status", incident.getStatus()
        ));
    }

    /**
     * 断点续跑：仅 FAILED 可触发，从最近一次失败的点继续（LLM 上下文从事件重建，工具幂等复用）。
     * 非 FAILED 状态返回 409。
     */
    @PostMapping("/{id}/resume")
    public ApiResponse<Map<String, Object>> resume(@PathVariable Long id) {
        Incident incident = incidentService.resumeAnalysis(id);
        analysisDispatcher.resume(incident.getId());
        return ApiResponse.ok(Map.of(
                "incidentId", incident.getId(),
                "status", incident.getStatus()
        ));
    }

    /**
     * 分析详情：RUNNING 返回状态与游标；COMPLETED 附完整报告；FAILED 附 errorMessage
     */
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
        data.put("phase", incident.getPhase());
        data.put("lastSeq", incident.getLastSeq());
        data.put("reportId", incident.getReportId());
        data.put("createdAt", incident.getCreatedAt());
        data.put("completedAt", incident.getCompletedAt());

        if ("FAILED".equals(incident.getStatus()) && incident.getErrorMessage() != null) {
            data.put("errorMessage", incident.getErrorMessage());
        }

        if ("COMPLETED".equals(incident.getStatus()) && incident.getReportId() != null) {
            AnalysisReport report = reportMapper.selectById(incident.getReportId());
            if (report != null) {
                data.put("report", convertReportToResponse(report));
            }
        }

        return ApiResponse.ok(data);
    }

    /**
     * 历史事件查询（只读）：返回该 incident 的全部落库事件，供 FAILED/COMPLETED 状态下
     * 前端重建已执行链路。RUNNING 状态走 SSE 重放，不依赖本端点。
     */
    @GetMapping("/{id}/events")
    public ApiResponse<List<Map<String, Object>>> getEvents(@PathVariable Long id) {
        if (incidentMapper.selectById(id) == null) {
            throw new ResourceNotFoundException("分析事件不存在");
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (IncidentEvent event : incidentEventMapper.selectAfter(id, 0)) {
            Map<String, Object> item = new HashMap<>();
            item.put("sequence", event.getSeq());
            item.put("type", event.getEventType());
            item.put("data", parseJsonArray(event.getPayload()));
            events.add(item);
        }
        return ApiResponse.ok(events);
    }

    /**
     * 报告采纳标注（评测口径见 docs/plan/EVALUATION_PLAN.md）：
     * adopted=true 赞同；false 不赞同，可附原因 issues（noise/evidence/sop，可空数组=未说明原因）
     */
    @PostMapping("/{id}/report/adoption")
    public ApiResponse<Map<String, Object>> submitAdoption(@PathVariable Long id,
                                                           @RequestBody Map<String, Object> body) {
        Incident incident = incidentMapper.selectById(id);
        if (incident == null) {
            throw new ResourceNotFoundException("分析事件不存在");
        }
        if (incident.getReportId() == null) {
            throw new ValidationException("该分析尚无报告，无法标注");
        }
        if (!(body.get("adopted") instanceof Boolean adopted)) {
            throw new ValidationException("adopted 必须为布尔值");
        }
        List<String> issues = List.of();
        if (!adopted && body.get("issues") != null) {
            if (!(body.get("issues") instanceof List<?> raw)) {
                throw new ValidationException("issues 必须为字符串数组");
            }
            issues = raw.stream().map(String::valueOf).toList();
            for (String issue : issues) {
                if (!ALLOWED_ADOPT_ISSUES.contains(issue)) {
                    throw new ValidationException("未知的原因类型: " + issue);
                }
            }
        }
        // 赞同（或未说明原因）时 issues 存 null，不存空数组
        String issuesJson;
        try {
            issuesJson = issues.isEmpty() ? null : objectMapper.writeValueAsString(issues);
        } catch (Exception e) {
            throw new IllegalStateException("issues 序列化失败", e);
        }
        reportMapper.updateAdoption(incident.getReportId(), adopted, issuesJson);
        return ApiResponse.ok(Map.of(
                "incidentId", id,
                "reportId", incident.getReportId(),
                "adopted", adopted,
                "adoptIssues", issues
        ));
    }

    /**
     * SSE 事件流：先注册再重放 seq > since 的落库事件（前端按 sequence 去重），
     * 之后转为实时推送。断点取 ?since= 或 Last-Event-ID 请求头。
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id,
                             @RequestParam(name = "since", defaultValue = "0") int since,
                             @RequestHeader(name = "Last-Event-ID", required = false) Integer lastEventId) {
        Incident incident = incidentMapper.selectById(id);
        if (incident == null) {
            throw new ResourceNotFoundException("分析事件不存在");
        }

        int from = lastEventId != null ? lastEventId : since;
        // 不设超时（靠心跳保活 + 断线自动清理），避免长分析被 60s 掐断
        SseEmitter emitter = new SseEmitter(0L);
        ssePushService.register(id, emitter);

        List<IncidentEvent> history = incidentEventMapper.selectAfter(id, from);
        for (IncidentEvent event : history) {
            String json = incidentEventService.toSseJson(event);
            if (json != null) {
                ssePushService.sendTo(emitter, json);
            }
        }
        return emitter;
    }

    /**
     * 报告实体 → 前端响应结构（四段格式 + confidence/confidenceReason）。
     * recommendedActions 读取侧归一：历史报告可能存的是字符串数组（写入侧归一上线前），
     * 统一转成 [{priority, action}]，保证接口永远输出结构正确且非空的 SOP 列表
     */
    private Map<String, Object> convertReportToResponse(AnalysisReport report) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", report.getId());
        data.put("isNoise", report.getIsNoise());
        data.put("needsHandling", report.getNeedsHandling());
        data.put("rootCauseHypothesis", report.getRootCauseHypothesis());
        data.put("confidence", report.getConfidence());
        data.put("confidenceReason", report.getConfidenceReason());
        Object parsedActions = parseJsonArray(report.getRecommendedActions());
        data.put("recommendedActions", RecommendedActionsNormalizer.normalize(
                parsedActions instanceof List<?> list ? list : List.of()));
        data.put("judgmentLogic", report.getJudgmentLogic());
        data.put("adopted", report.getAdopted());
        Object parsedIssues = parseJsonArray(report.getAdoptIssues());
        data.put("adoptIssues", parsedIssues instanceof List<?> list ? list : List.of());
        return data;
    }

    /**
     * JSON 数组字符串 → 对象，解析失败原样返回字符串
     */
    private Object parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
