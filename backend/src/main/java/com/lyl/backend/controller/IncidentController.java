package com.lyl.backend.controller;

import com.lyl.backend.model.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    @PostMapping("/{alertId}/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long alertId) {
        return ApiResponse.ok(Map.of(
                "incidentId", 0,
                "status", "RUNNING"
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getIncident(@PathVariable Long id) {
        return ApiResponse.ok(Map.of(
                "id", id,
                "alertId", 0,
                "status", "PENDING",
                "reportId", 0,
                "createdAt", "1970-01-01 00:00:00",
                "completedAt", "1970-01-01 00:00:00"
        ));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) throws IOException {
        SseEmitter emitter = new SseEmitter(10_000L);
        emitter.send(SseEmitter.event()
                .name("message")
                .data("{\"sequence\":0,\"type\":\"step\",\"data\":{\"message\":\"placeholder\"}}"));
        emitter.complete();
        return emitter;
    }
}
