package com.lyl.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyl.backend.mapper.ReportMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.AnalysisReport;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Mock 分析执行器：无 LLM，推送固定事件序列并落库写死的报告。
 * 用于测试环境（app.analysis.executor=mock）和 LLM 异常时的对照。
 */
@Slf4j
@Service
public class MockAnalysisExecutor implements AnalysisExecutor {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SsePushService ssePushService;
    private final IncidentEventService eventService;
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    public MockAnalysisExecutor(SsePushService ssePushService,
                                IncidentEventService eventService,
                                ReportMapper reportMapper,
                                ObjectMapper objectMapper) {
        this.ssePushService = ssePushService;
        this.eventService = eventService;
        this.reportMapper = reportMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(Incident incident, Alert alert) {
        // 1. 开始分析
        ObjectNode received = objectMapper.createObjectNode()
                .put("alertName", alert.getAlertName())
                .put("service", alert.getService())
                .put("severity", alert.getSeverity())
                .put("startsAt", alert.getStartsAt());
        eventService.appendAndPush(ssePushService, incident, "received", "incident_received", received);

        // 2. 思考
        ObjectNode thought = objectMapper.createObjectNode()
                .put("thought", "[mock] 匹配到手册：CPU 异常排查，先查指标再查日志");
        eventService.appendAndPush(ssePushService, incident, "thinking", "agent_thought", thought);

        // 3. 工具：getMetrics
        ObjectNode metricsCall = objectMapper.createObjectNode()
                .put("tool", "getMetrics")
                .put("args", "{\"service\":\"" + alert.getService() + "\",\"metricName\":\"cpu\"}")
                .put("stepSeq", 1);
        eventService.appendAndPush(ssePushService, incident, "tool_running", "tool_call_start", metricsCall);

        ObjectNode metricsResult = objectMapper.createObjectNode()
                .put("tool", "getMetrics").put("success", true)
                .put("summary", "查阅 12 条指标")
                .put("detail", "查找 22:13–22:28 服务 " + alert.getService() + " 名称含 cpu 的指标，找到 12 条。");
        eventService.appendAndPush(ssePushService, incident, "tool_running", "tool_call_result", metricsResult);

        ObjectNode evidence1 = objectMapper.createObjectNode()
                .put("source", "metrics")
                .put("content", "最新值 95（22:28），峰值 95（22:23）；共 12 条")
                .put("timestamp", "2025-01-15 22:23:00");
        eventService.appendAndPush(ssePushService, incident, null, "evidence_collected", evidence1);

        // 4. 工具：getLogs
        ObjectNode logsCall = objectMapper.createObjectNode()
                .put("tool", "getLogs")
                .put("args", "{\"service\":\"" + alert.getService() + "\",\"keyword\":\"error\"}")
                .put("stepSeq", 2);
        eventService.appendAndPush(ssePushService, incident, "tool_running", "tool_call_start", logsCall);

        ObjectNode logsResult = objectMapper.createObjectNode()
                .put("tool", "getLogs").put("success", true)
                .put("summary", "查阅 5 条日志")
                .put("detail", "查找 22:13–22:28 服务 " + alert.getService() + " 包含 error 的日志，找到 5 条。");
        eventService.appendAndPush(ssePushService, incident, "tool_running", "tool_call_result", logsResult);

        ObjectNode evidence2 = objectMapper.createObjectNode()
                .put("source", "logs")
                .put("content", "最新（22:33:05）：检测到异常高 CPU 占用程序；共 5 条")
                .put("timestamp", "2025-01-15 22:33:05");
        eventService.appendAndPush(ssePushService, incident, null, "evidence_collected", evidence2);

        // 5. 最终思考
        ObjectNode conclusion = objectMapper.createObjectNode()
                .put("thought", "[mock] [观察结论] 异常进程占用 CPU，指标与日志互相印证");
        eventService.appendAndPush(ssePushService, incident, "finalizing", "agent_thought", conclusion);

        // 6. 报告落库 + report_finalized
        AnalysisReport report = new AnalysisReport();
        report.setIsNoise(false);
        report.setNeedsHandling(true);
        report.setRootCauseHypothesis(alert.getService() + " 存在异常进程持续占用 CPU，导致服务响应变慢，进而影响支付服务");
        report.setConfidence(0.85);
        report.setRecommendedActions(
                "[{\"priority\":1,\"action\":\"检查 " + alert.getService() + " 上的异常进程\"}," +
                "{\"priority\":2,\"action\":\"如确认异常进程，考虑重启该服务\"}]");
        report.setJudgmentLogic("1. getMetrics 显示 CPU 使用率 22:23 起升至 95%（来源 metrics）。\n2. getLogs 在同时段发现异常高 CPU 占用程序日志（来源 logs），两者时间吻合，得出上述根因假设。");
        report.setConfidenceReason("指标与日志两类证据相互印证");
        reportMapper.insert(report);

        incident.setStatus(IncidentStatus.COMPLETED.name());
        incident.setReportId(report.getId());
        incident.setCompletedAt(LocalDateTime.now().format(FMT));

        ObjectNode finalized = objectMapper.createObjectNode().put("reportId", report.getId());
        eventService.appendAndPush(ssePushService, incident, "done", "report_finalized", finalized);

        log.info("Mock analysis completed for incident {}", incident.getId());
    }
}
