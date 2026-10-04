package com.lyl.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyl.backend.config.AnalysisProperties;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 崩溃恢复：启动时扫描超时的 RUNNING Incident（updated_at 超过 stale-minutes），
 * 标记 FAILED 并写 error 事件。不做断点续跑——工具全是只读查询，用户手动重试无副作用。
 */
@Slf4j
@Component
public class CrashRecoveryRunner implements ApplicationRunner {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IncidentMapper incidentMapper;
    private final IncidentEventService eventService;
    private final SsePushService ssePushService;
    private final AnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public CrashRecoveryRunner(IncidentMapper incidentMapper,
                               IncidentEventService eventService,
                               SsePushService ssePushService,
                               AnalysisProperties properties,
                               ObjectMapper objectMapper) {
        this.incidentMapper = incidentMapper;
        this.eventService = eventService;
        this.ssePushService = ssePushService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 应用启动后执行恢复扫描
     */
    @Override
    public void run(ApplicationArguments args) {
        String threshold = LocalDateTime.now().minusMinutes(properties.getStaleMinutes()).format(FMT);
        List<Incident> stale = incidentMapper.selectStaleRunning(threshold);
        for (Incident incident : stale) {
            log.warn("Crash recovery: incident {} stuck in RUNNING since {}, marking FAILED",
                    incident.getId(), incident.getUpdatedAt());
            incident.setStatus(IncidentStatus.FAILED.name());
            incident.setErrorMessage("分析中断，请重试（服务重启导致）");
            eventService.appendAndPush(ssePushService, incident, null, "error",
                    objectMapper.createObjectNode().put("message", "分析中断，请重试（服务重启导致）"));
        }
        if (!stale.isEmpty()) {
            log.info("Crash recovery marked {} incident(s) as FAILED", stale.size());
        }
    }
}
