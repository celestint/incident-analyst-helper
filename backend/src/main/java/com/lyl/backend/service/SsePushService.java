package com.lyl.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 推送服务：管理活跃连接，向订阅者推送事件。
 * - 一个 incident 允许多个订阅者（多标签页/重连过渡期）
 * - SseEmitter 非线程安全：所有发送按 emitter 对象加锁串行
 * - 推送失败只移除连接，不影响分析流程（事件已落库，可重连重放）
 */
@Slf4j
@Service
public class SsePushService {

    /** incidentId -> 活跃 emitter 集合 */
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 注册订阅连接，断开/超时/完成时自动清理
     */
    public void register(Long incidentId, SseEmitter emitter) {
        emitters.computeIfAbsent(incidentId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> {
            Set<SseEmitter> set = emitters.get(incidentId);
            if (set != null) {
                set.remove(emitter);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
    }

    /**
     * 向 incident 的全部订阅者推送事件 JSON（{"sequence":n,"type":t,"data":{...}}）
     */
    public void push(Long incidentId, String eventJson) {
        Set<SseEmitter> set = emitters.get(incidentId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            sendTo(emitter, eventJson);
        }
    }

    /**
     * 向单个 emitter 发送事件，发送失败时移除该连接（内部消化异常，不向上抛）
     */
    public void sendTo(SseEmitter emitter, String eventJson) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("message").data(eventJson));
            }
        } catch (Exception e) {
            log.debug("SSE send failed, dropping emitter: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /**
     * 心跳：每 15 秒向所有活跃连接发 SSE 注释行，防止网关/代理空闲超时断连
     */
    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        emitters.forEach((incidentId, set) -> set.forEach(emitter -> {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (Exception e) {
                set.remove(emitter);
            }
        }));
    }
}
