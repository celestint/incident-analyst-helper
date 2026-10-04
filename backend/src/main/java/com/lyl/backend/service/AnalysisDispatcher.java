package com.lyl.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyl.backend.config.AnalysisProperties;
import com.lyl.backend.mapper.AlertMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 分析调度器：按 app.analysis.executor 配置分流到 mock / llm 执行器，
 * 统一处理执行异常（error 事件 + FAILED + errorMessage）。
 */
@Slf4j
@Service
public class AnalysisDispatcher {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final MockAnalysisExecutor mockExecutor;
    private final ObjectProvider<LlmAnalysisExecutor> llmExecutorProvider;
    private final AnalysisProperties properties;
    private final IncidentMapper incidentMapper;
    private final AlertMapper alertMapper;
    private final IncidentEventService eventService;
    private final SsePushService ssePushService;
    private final ObjectMapper objectMapper;

    public AnalysisDispatcher(MockAnalysisExecutor mockExecutor,
                              ObjectProvider<LlmAnalysisExecutor> llmExecutorProvider,
                              AnalysisProperties properties,
                              IncidentMapper incidentMapper,
                              AlertMapper alertMapper,
                              IncidentEventService eventService,
                              SsePushService ssePushService,
                              ObjectMapper objectMapper) {
        this.mockExecutor = mockExecutor;
        this.llmExecutorProvider = llmExecutorProvider;
        this.properties = properties;
        this.incidentMapper = incidentMapper;
        this.alertMapper = alertMapper;
        this.eventService = eventService;
        this.ssePushService = ssePushService;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步执行分析（分析专用线程池）。事件已由 start 接口落库，这里只负责跑执行器。
     */
    @Async("analysisExecutor")
    public void dispatch(Long incidentId) {
        execute(incidentId, false);
    }

    /**
     * 异步断点续跑：从最近一次失败的点继续（LLM 上下文从事件重建，工具幂等复用）
     */
    @Async("analysisExecutor")
    public void resume(Long incidentId) {
        execute(incidentId, true);
    }

    private void execute(Long incidentId, boolean isResume) {
        Incident incident = incidentMapper.selectById(incidentId);
        if (incident == null) {
            log.error("Incident not found, skip analysis: {}", incidentId);
            return;
        }
        try {
            if ("llm".equals(properties.getExecutor())) {
                LlmAnalysisExecutor executor = llmExecutorProvider.getIfAvailable();
                if (executor == null) {
                    throw new IllegalStateException("LLM 执行器未装配：请配置 ai.base-url/ai.api-key/ai.model");
                }
                if (isResume) {
                    executor.resume(incident, loadAlert(incident));
                } else {
                    executor.run(incident, loadAlert(incident));
                }
            } else {
                mockExecutor.run(incident, loadAlert(incident));
            }
        } catch (Exception e) {
            log.error("Analysis failed for incident {}", incidentId, e);
            markFailed(incident, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 失败兜底：写 error 事件 + 状态置 FAILED + errorMessage，同一事务
     */
    public void markFailed(Incident incident, String message) {
        try {
            incident.setStatus(IncidentStatus.FAILED.name());
            incident.setErrorMessage(abbreviate(message, MAX_ERROR_MESSAGE_LENGTH));
            ObjectNode payload = objectMapper.createObjectNode().put("message", message);
            eventService.appendAndPush(ssePushService, incident, null, "error", payload);
        } catch (Exception e) {
            log.error("Failed to mark incident {} as FAILED", incident.getId(), e);
        }
    }

    private Alert loadAlert(Incident incident) {
        Alert alert = alertMapper.selectById(incident.getAlertId());
        if (alert == null) {
            throw new IllegalStateException("告警不存在: " + incident.getAlertId());
        }
        return alert;
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(截断)";
    }
}
