package com.lyl.backend.service;

import com.lyl.backend.config.DataAnalyticsConfig;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataAnalyticsService {

    private final DataAnalyticsConfig config;

    public DataAnalyticsService(DataAnalyticsConfig config) {
        this.config = config;
    }

    /**
     * 查询日志
     */
    public Map<String, Object> getLogs(String service, String keyword, Long startTime, Long endTime, Integer limit) {
        StringBuilder sql = new StringBuilder("SELECT timestamp, log_name, value FROM logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (service != null && !service.isEmpty()) {
            sql.append(" AND service = ?");
            params.add(service);
        }

        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND value ILIKE ?");
            params.add("%" + keyword + "%");
        }

        if (startTime != null) {
            sql.append(" AND timestamp >= ?");
            params.add(startTime);
        }

        if (endTime != null) {
            sql.append(" AND timestamp <= ?");
            params.add(endTime);
        }

        sql.append(" ORDER BY timestamp DESC LIMIT ?");
        params.add(limit != null && limit > 0 ? limit : 100);

        return executeQuery(sql.toString(), params);
    }

    /**
     * 统计日志数量
     */
    public int countLogs(String service, String keyword, Long startTime, Long endTime) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) as total FROM logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (service != null && !service.isEmpty()) {
            sql.append(" AND service = ?");
            params.add(service);
        }

        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND value ILIKE ?");
            params.add("%" + keyword + "%");
        }

        if (startTime != null) {
            sql.append(" AND timestamp >= ?");
            params.add(startTime);
        }

        if (endTime != null) {
            sql.append(" AND timestamp <= ?");
            params.add(endTime);
        }

        try (Connection conn = config.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("total");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count logs", e);
        }

        return 0;
    }

    /**
     * 查询指标
     */
    public Map<String, Object> getMetrics(String service, String metricName, Long startTime, Long endTime, Integer limit) {
        StringBuilder sql = new StringBuilder("SELECT timestamp, kpi_name, value FROM metrics WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (service != null && !service.isEmpty()) {
            sql.append(" AND service = ?");
            params.add(service);
        }

        if (metricName != null && !metricName.isEmpty()) {
            sql.append(" AND kpi_name ILIKE ?");
            params.add("%" + metricName + "%");
        }

        if (startTime != null) {
            sql.append(" AND timestamp >= ?");
            params.add(startTime);
        }

        if (endTime != null) {
            sql.append(" AND timestamp <= ?");
            params.add(endTime);
        }

        sql.append(" ORDER BY timestamp DESC LIMIT ?");
        params.add(limit != null && limit > 0 ? limit : 100);

        return executeQuery(sql.toString(), params);
    }

    /**
     * 查询 KPI
     */
    public Map<String, Object> getKpi(String service, Long startTime, Long endTime, Integer limit) {
        StringBuilder sql = new StringBuilder("SELECT timestamp, rr, sr, cnt, mrt FROM kpi WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (service != null && !service.isEmpty()) {
            sql.append(" AND service = ?");
            params.add(service);
        }

        if (startTime != null) {
            sql.append(" AND timestamp >= ?");
            params.add(startTime);
        }

        if (endTime != null) {
            sql.append(" AND timestamp <= ?");
            params.add(endTime);
        }

        sql.append(" ORDER BY timestamp DESC LIMIT ?");
        params.add(limit != null && limit > 0 ? limit : 100);

        return executeQuery(sql.toString(), params);
    }

    /**
     * 查询慢调用 trace
     */
    public Map<String, Object> getSlowSpans(String service, Long startTime, Long endTime, Integer limit) {
        StringBuilder sql = new StringBuilder("SELECT timestamp, trace_id, span_id, parent_id, duration FROM traces WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (service != null && !service.isEmpty()) {
            sql.append(" AND service = ?");
            params.add(service);
        }

        if (startTime != null) {
            sql.append(" AND timestamp >= ?");
            params.add(startTime);
        }

        if (endTime != null) {
            sql.append(" AND timestamp <= ?");
            params.add(endTime);
        }

        sql.append(" ORDER BY duration DESC LIMIT ?");
        params.add(limit != null && limit > 0 ? limit : 10);

        return executeQuery(sql.toString(), params);
    }

    /**
     * 执行查询并返回结果
     */
    private Map<String, Object> executeQuery(String sql, List<Object> params) {
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection conn = config.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                items.add(row);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("total", items.size());
            result.put("items", items);

            return result;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute query: " + sql, e);
        }
    }
}
