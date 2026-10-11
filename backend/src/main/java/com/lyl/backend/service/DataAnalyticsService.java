package com.lyl.backend.service;

import com.lyl.backend.config.DataAnalyticsConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    /** 内存占比派生指标依赖的原始指标名（mock 数据的 OSLinux 内存指标） */
    private static final String METRIC_USER_MEM = "OSLinux-OSLinux_MEMORY_MEMORY_UserMem";
    private static final String METRIC_CACHE_MEM = "OSLinux-OSLinux_MEMORY_MEMORY_CacheMem";
    private static final String METRIC_MEM_FREE = "OSLinux-OSLinux_MEMORY_MEMORY_MEMFreeMem";
    private static final String METRIC_NO_CACHE_PERC = "OSLinux-OSLinux_MEMORY_MEMORY_NoCacheMemPerc";
    private static final String METRIC_USED_PERC = "OSLinux-OSLinux_MEMORY_MEMORY_MEMUsedMemPerc";

    private static final DateTimeFormatter MEMORY_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 内存占比查询（内存类告警专用）：查原始内存指标行，按时间点分组，
     * 每点优先取 NoCacheMemPerc/MEMUsedMemPerc 原始值，缺失时按手册公式计算，数据不全的时间点跳过。
     * 返回 {total, items:[{time, noCacheMemPerc, memUsedMemPerc}]}，按时间升序，limit 限制返回的时间点数
     */
    public Map<String, Object> getMemoryUsage(String service, Long startTime, Long endTime, Integer limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT timestamp, kpi_name, value FROM metrics WHERE kpi_name IN (?, ?, ?, ?, ?)");
        List<Object> params = new ArrayList<>(List.of(
                METRIC_USER_MEM, METRIC_CACHE_MEM, METRIC_MEM_FREE, METRIC_NO_CACHE_PERC, METRIC_USED_PERC));

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
        sql.append(" ORDER BY timestamp ASC");

        Map<String, Object> raw = executeQuery(sql.toString(), params);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                toMemoryUsageItems((List<Map<String, Object>>) raw.getOrDefault("items", List.of()));

        int maxPoints = limit != null && limit > 0 ? limit : 100;
        if (items.size() > maxPoints) {
            items = items.subList(0, maxPoints);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 原始内存指标行（timestamp/kpi_name/value）→ 按时间点升序的占比列表。
     * 每点 NoCacheMemPerc/MEMUsedMemPerc 优先取原始值，缺失时按手册公式
     * （NoCacheMemPerc=(UserMem-CacheMem)/(UserMem+MEMFreeMem)×100、MEMUsedMemPerc=UserMem/(UserMem+MEMFreeMem)×100）
     * 计算；占比不可得（指标缺失或分母为 0）的时间点跳过。百分比保留 1 位小数（HALF_UP）。
     */
    static List<Map<String, Object>> toMemoryUsageItems(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Double>> points = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            if (!(row.get("timestamp") instanceof Number ts) || !(row.get("value") instanceof Number value)
                    || row.get("kpi_name") == null) {
                continue;
            }
            points.computeIfAbsent(ts.longValue(), k -> new HashMap<>())
                    .put(row.get("kpi_name").toString(), value.doubleValue());
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Double>> entry : points.entrySet()) {
            Map<String, Double> point = entry.getValue();
            Double noCache = memoryPerc(point, METRIC_NO_CACHE_PERC, true);
            Double used = memoryPerc(point, METRIC_USED_PERC, false);
            if (noCache == null || used == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", Instant.ofEpochSecond(entry.getKey())
                    .atZone(ZoneId.systemDefault()).format(MEMORY_TIME_FMT));
            item.put("noCacheMemPerc", percent(noCache));
            item.put("memUsedMemPerc", percent(used));
            items.add(item);
        }
        return items;
    }

    /**
     * 单点占比：directKey 命中取原始值；否则按 noCache 公式从 UserMem/CacheMem/MEMFreeMem 计算，不可得返回 null
     */
    private static Double memoryPerc(Map<String, Double> point, String directKey, boolean noCache) {
        Double direct = point.get(directKey);
        if (direct != null) {
            return direct;
        }
        Double user = point.get(METRIC_USER_MEM);
        Double free = point.get(METRIC_MEM_FREE);
        if (user == null || free == null) {
            return null;
        }
        double denominator = user + free;
        if (denominator == 0) {
            return null;
        }
        if (noCache) {
            Double cache = point.get(METRIC_CACHE_MEM);
            if (cache == null) {
                return null;
            }
            return (user - cache) / denominator * 100;
        }
        return user / denominator * 100;
    }

    private static String percent(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
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
     * 统计窗口内调用 span 总数：判定"调用链数据缺失"的依据（慢调用查空不代表没有 trace 数据，
     * 双窗口 span 总数均为 0 才是缺失）。与 countLogs 同为裸数字返回，total=0 自然计入止损计数。
     */
    public int getTraceCount(String service, Long startTime, Long endTime) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) as total FROM traces WHERE 1=1");
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
            throw new RuntimeException("Failed to count traces", e);
        }

        return 0;
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
