package com.lyl.backend.controller;

import com.lyl.backend.model.ApiResponse;
import com.lyl.backend.tool.MockDataLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final MockDataLoader mockDataLoader;

    public AlertController(MockDataLoader mockDataLoader) {
        this.mockDataLoader = mockDataLoader;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> createAlert() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", 0);
        data.put("alertName", "placeholder");
        data.put("severity", "warning");
        data.put("service", "placeholder");
        data.put("startsAt", "1970-01-01 00:00:00");
        data.put("labels", Map.of());
        data.put("incidentId", null);
        data.put("status", "PENDING");
        return ApiResponse.ok(data);
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listAlerts() {
        List<Map<String, Object>> alerts = mockDataLoader.loadAlerts();

        // 转换为前端需要的格式（添加 incidentId 和 status）
        for (Map<String, Object> alert : alerts) {
            alert.put("incidentId", null);
            alert.put("status", "PENDING");
        }

        return ApiResponse.ok(alerts);
    }
}
