package com.lyl.backend.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MockDataLoader {

    private final ObjectMapper objectMapper;

    public MockDataLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadAlerts() {
        try (InputStream is = new ClassPathResource("mock-data/alerts.json").getInputStream()) {
            return objectMapper.readValue(is, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> loadLogs() {
        try (InputStream is = new ClassPathResource("mock-data/logs.json").getInputStream()) {
            return objectMapper.readValue(is, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> loadMetrics() {
        try (InputStream is = new ClassPathResource("mock-data/metrics.json").getInputStream()) {
            return objectMapper.readValue(is, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> loadTraces() {
        try (InputStream is = new ClassPathResource("mock-data/traces.json").getInputStream()) {
            return objectMapper.readValue(is, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
