package com.lyl.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyl.backend.mapper.IncidentEventMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 过程事件写入服务。
 * 保证"写 incident_events + 更新 incident.last_seq/phase/updated_at"在同一事务；
 * 事务提交后才推 SSE（方法返回即事务已提交）。
 */
@Slf4j
@Service
public class IncidentEventService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IncidentEventMapper eventMapper;
    private final IncidentMapper incidentMapper;
    private final ObjectMapper objectMapper;

    public IncidentEventService(IncidentEventMapper eventMapper,
                                IncidentMapper incidentMapper,
                                ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.incidentMapper = incidentMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加事件并推进 incident 游标，返回构建好的 SSE 事件 JSON（{"sequence":n,"type":t,"data":{...}}）。
     * phase 传 null 表示不改变当前阶段。
     */
    @Transactional
    public String appendEvent(Incident incident, String phase, String eventType, ObjectNode payload) {
        int seq = (incident.getLastSeq() == null ? 0 : incident.getLastSeq()) + 1;

        IncidentEvent event = new IncidentEvent();
        event.setIncidentId(incident.getId());
        event.setSeq(seq);
        event.setEventType(eventType);
        event.setPayload(payload == null ? null : payload.toString());
        event.setCreatedAt(LocalDateTime.now().format(FMT));
        eventMapper.insert(event);

        incident.setLastSeq(seq);
        if (phase != null) {
            incident.setPhase(phase);
        }
        incident.setUpdatedAt(LocalDateTime.now().format(FMT));
        incidentMapper.update(incident);

        ObjectNode sseEvent = objectMapper.createObjectNode();
        sseEvent.put("sequence", seq);
        sseEvent.put("type", eventType);
        sseEvent.set("data", payload == null ? objectMapper.createObjectNode() : payload);
        return sseEvent.toString();
    }

    /**
     * 追加事件后推送给 SSE 订阅者。
     * 事务保证事件与状态同写；推送注册在事务提交之后执行（提交失败不推送）。
     */
    @Transactional
    public void appendAndPush(SsePushService ssePushService, Incident incident, String phase,
                              String eventType, ObjectNode payload) {
        String eventJson = appendEvent(incident, phase, eventType, payload);
        Long incidentId = incident.getId();
        Runnable push = () -> {
            try {
                ssePushService.push(incidentId, eventJson);
            } catch (Exception e) {
                // 推送失败不影响分析流程：事件已落库，前端可重连重放
                log.warn("SSE push failed for incident {}: {}", incidentId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }

    /**
     * 把已落库的事件对象序列化为 SSE 事件 JSON（重放用）
     */
    public String toSseJson(IncidentEvent event) {
        try {
            ObjectNode sseEvent = objectMapper.createObjectNode();
            sseEvent.put("sequence", event.getSeq());
            sseEvent.put("type", event.getEventType());
            sseEvent.set("data", event.getPayload() == null
                    ? objectMapper.createObjectNode()
                    : (com.fasterxml.jackson.databind.JsonNode) objectMapper.readTree(event.getPayload()));
            return sseEvent.toString();
        } catch (Exception e) {
            log.error("Failed to serialize event {} for replay", event.getId(), e);
            return null;
        }
    }
}
