package com.lyl.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyl.backend.tool.ToolContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 工具调用语义化文案生成：把工具的参数与结果 JSON 提炼为用户可读的三段文案。
 * - collapsed：折叠头一句话（如"查阅 1 文件"）
 * - detail：展开描述（如"查找 11:40–12:00 服务 apache01 名称含 memory 的指标，找到 0 条。"）
 * - evidence：证据结论文案（从数据提炼数值/时间点/计数，证据标签由调用方按工具名给出）
 * 结果 JSON 解析失败时整体降级：返回 null，由调用方退回截断原始文本的旧行为。
 */
@Service
public class ToolCallSummarizer {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper objectMapper;

    public ToolCallSummarizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 语义化文案
     */
    public record Display(String collapsed, String detail, String evidence) {
    }

    /**
     * 按工具生成语义化文案。仅处理成功结果；失败路径由调用方走 error 字段。
     */
    public Display summarize(String toolName, String argsJson, String resultText, ToolContext ctx) {
        try {
            Map<String, Object> args = parseArgs(argsJson);
            JsonNode result = resultText == null || resultText.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(resultText);
            return switch (toolName) {
                case "getRunbook" -> runbook(result);
                case "getLogs" -> logs(args, ctx, result);
                case "countLogs" -> countLogs(args, ctx, resultText);
                case "getMetrics" -> metrics(args, ctx, result);
                case "getKpi" -> kpi(args, ctx, result);
                case "getSlowSpans" -> slowSpans(args, ctx, result);
                case "getTraceCount" -> traceCount(args, ctx, resultText);
                case "getMemoryUsage" -> memoryUsage(args, ctx, result);
                case "calculate" -> calculate(args, result);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private Display runbook(JsonNode result) {
        if (!result.path("matched").asBoolean(false)) {
            return new Display("查阅 0 文件", "未找到匹配的运维手册，转入自主排查", "无匹配手册，已转入自主排查");
        }
        String file = result.path("file").asText("");
        int steps = countHeadings(result.path("content").asText(""));
        String evidence = steps > 0
                ? "命中《" + file + "》，含 " + steps + " 个排查步骤"
                : "命中《" + file + "》";
        return new Display("查阅 1 文件", "读取 " + file, evidence);
    }

    private Display logs(Map<String, Object> args, ToolContext ctx, JsonNode result) {
        int total = result.path("total").asInt(0);
        JsonNode items = result.path("items");
        String keyword = str(args.get("keyword"));
        String detail = "查找 " + service(args, ctx) + range(args, ctx)
                + (keyword == null ? "" : "包含 " + keyword + " ") + "的日志，找到 " + total + " 条。";
        String evidence;
        if (total == 0 || !items.isArray() || items.isEmpty()) {
            evidence = "未查询到日志";
        } else {
            // items 按 timestamp DESC 排列，首条即最新
            JsonNode latest = items.get(0);
            String value = abbreviate(latest.path("value").asText(""), 60);
            evidence = "最新（" + time(latest.path("timestamp")) + "）：" + value + "；共 " + total + " 条";
        }
        return new Display("查阅 " + total + " 条日志", detail, evidence);
    }

    private Display countLogs(Map<String, Object> args, ToolContext ctx, String resultText) {
        long total;
        try {
            total = Long.parseLong(resultText == null ? "0" : resultText.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String keyword = str(args.get("keyword"));
        String detail = "统计 " + service(args, ctx) + range(args, ctx)
                + (keyword == null ? "" : "包含 " + keyword + " ") + "的日志数量：" + total + " 条";
        return new Display("查阅 " + total + " 条日志", detail, total > 0 ? "共 " + total + " 条" : "未查询到日志");
    }

    /**
     * getTraceCount 文案：裸数字结果（镜像 countLogs），展示 span 总数统计。
     * 0 条时证据写"未查询到调用链数据"——与 getSlowSpans 的"未查询到慢调用"区分：
     * 前者才是判定"调用链数据缺失"的依据。
     */
    private Display traceCount(Map<String, Object> args, ToolContext ctx, String resultText) {
        long total;
        try {
            total = Long.parseLong(resultText == null ? "0" : resultText.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String detail = "统计 " + service(args, ctx) + range(args, ctx) + "的调用 span 总数：" + total + " 条";
        return new Display("查阅 调用链", detail, total > 0 ? "共 " + total + " 条" : "未查询到调用链数据");
    }

    private Display metrics(Map<String, Object> args, ToolContext ctx, JsonNode result) {
        int total = result.path("total").asInt(0);
        JsonNode items = result.path("items");
        String metricName = str(args.get("metricName"));
        String detail = "查找 " + service(args, ctx) + range(args, ctx)
                + (metricName == null ? "" : "名称含 " + metricName + " ") + "的指标，找到 " + total + " 条。";
        String evidence;
        if (total == 0 || !items.isArray() || items.isEmpty()) {
            evidence = "未查询到数据";
        } else {
            // items 按 timestamp DESC 排列：首条即最新值；全量扫峰值
            JsonNode latest = items.get(0);
            double peak = Double.NEGATIVE_INFINITY;
            String peakTime = null;
            for (JsonNode item : items) {
                double value = item.path("value").asDouble(Double.NaN);
                if (!Double.isNaN(value) && value > peak) {
                    peak = value;
                    peakTime = time(item.path("timestamp"));
                }
            }
            StringBuilder sb = new StringBuilder("最新值 ").append(fmtValue(latest.path("value").asDouble(Double.NaN)))
                    .append("（").append(time(latest.path("timestamp"))).append("）");
            if (peakTime != null) {
                sb.append("，峰值 ").append(fmtValue(peak)).append("（").append(peakTime).append("）");
            }
            sb.append("；共 ").append(total).append(" 条");
            evidence = sb.toString();
        }
        return new Display("查阅 " + total + " 条指标", detail, evidence);
    }

    private Display kpi(Map<String, Object> args, ToolContext ctx, JsonNode result) {
        int total = result.path("total").asInt(0);
        JsonNode items = result.path("items");
        String detail = "查找 " + service(args, ctx) + range(args, ctx) + "的 KPI 数据，找到 " + total + " 条。";
        String evidence;
        if (total == 0 || !items.isArray() || items.isEmpty()) {
            evidence = "未查询到数据";
        } else {
            JsonNode latest = items.get(0);
            evidence = "最新（" + time(latest.path("timestamp")) + "）：请求量 " + latest.path("rr").asText("?")
                    + "，成功率 " + latest.path("sr").asText("?")
                    + "，平均响应 " + latest.path("mrt").asText("?")
                    + "；共 " + total + " 条";
        }
        return new Display("查阅 " + total + " 条指标", detail, evidence);
    }

    private Display slowSpans(Map<String, Object> args, ToolContext ctx, JsonNode result) {
        int total = result.path("total").asInt(0);
        JsonNode items = result.path("items");
        int limit = intOr(args.get("limit"), 10);
        String detail = "查找 " + service(args, ctx) + range(args, ctx) + "最慢的 " + limit + " 个调用，找到 " + total + " 条。";
        String evidence;
        if (total == 0 || !items.isArray() || items.isEmpty()) {
            evidence = "未查询到慢调用";
        } else {
            // 按 duration DESC，首条即最慢
            JsonNode slowest = items.get(0);
            evidence = "最慢调用 " + fmtValue(slowest.path("duration").asDouble(Double.NaN)) + "ms（trace_id="
                    + slowest.path("trace_id").asText("?") + "，" + time(slowest.path("timestamp")) + "）";
        }
        return new Display("查阅 调用链", detail, evidence);
    }

    /**
     * 内存占比工具：items 按时间升序，末条即最新；证据提炼最新两点占比与 NoCacheMemPerc 峰值
     */
    private Display memoryUsage(Map<String, Object> args, ToolContext ctx, JsonNode result) {
        int total = result.path("total").asInt(0);
        JsonNode items = result.path("items");
        String detail = "查找 " + service(args, ctx) + range(args, ctx) + "的内存占比，得到 " + total + " 个时间点。";
        String evidence;
        if (total == 0 || !items.isArray() || items.isEmpty()) {
            evidence = "未查询到数据";
        } else {
            JsonNode latest = items.get(items.size() - 1);
            String hhmm = hhmmOf(latest.path("time").asText(""));
            StringBuilder sb = new StringBuilder("最新（").append(hhmm)
                    .append("）NoCacheMemPerc ").append(latest.path("noCacheMemPerc").asText("?"))
                    .append("、MEMUsedMemPerc ").append(latest.path("memUsedMemPerc").asText("?"));
            String peakText = peakPerc(items);
            if (peakText != null) {
                sb.append("，峰值 NoCacheMemPerc ").append(peakText);
            }
            sb.append("；共 ").append(total).append(" 个时间点");
            evidence = sb.toString();
        }
        return new Display("查阅 " + total + " 条指标", detail, evidence);
    }

    /** "2021-03-04 11:50:00" → "11:50"，格式不符返回 "?" */
    private String hhmmOf(String time) {
        return time.length() >= 16 ? time.substring(11, 16) : "?";
    }

    /** 扫描 items 取 noCacheMemPerc 峰值（"80.1%" 形式），无可解析值返回 null */
    private String peakPerc(JsonNode items) {
        double peak = Double.NEGATIVE_INFINITY;
        String peakText = null;
        for (JsonNode item : items) {
            String text = item.path("noCacheMemPerc").asText("");
            if (!text.endsWith("%")) {
                continue;
            }
            try {
                double value = Double.parseDouble(text.substring(0, text.length() - 1));
                if (value > peak) {
                    peak = value;
                    peakText = text;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return peakText;
    }

    private Display calculate(Map<String, Object> args, JsonNode result) {
        String expression = str(args.get("expression"));
        if (expression == null) {
            expression = result.path("expression").asText("");
        }
        String detail = "计算 " + abbreviate(expression, 40);
        if (!result.path("value").isNumber()) {
            return new Display("执行计算", detail, null);
        }
        return new Display("执行计算", detail + "，结果 " + fmtCalcValue(result.path("value").asDouble()), null);
    }

    // ---- 公共片段 ----

    private String service(Map<String, Object> args, ToolContext ctx) {
        Object service = args.get("service");
        if (service != null && !service.toString().isBlank()) {
            return service.toString();
        }
        return ctx.defaultService() == null || ctx.defaultService().isBlank() ? "告警服务" : ctx.defaultService();
    }

    /**
     * " 11:40–12:00 " 形式的时间窗片段；args 未传时取告警默认窗口
     */
    private String range(Map<String, Object> args, ToolContext ctx) {
        long start = args.get("startTime") instanceof Number n ? n.longValue() : ctx.windowStart();
        long end = args.get("endTime") instanceof Number n ? n.longValue() : ctx.windowEnd();
        return " " + time(start) + "–" + time(end) + " ";
    }

    private String time(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(HH_MM);
    }

    private String time(JsonNode timestamp) {
        return timestamp.canConvertToLong() ? time(timestamp.asLong()) : "?";
    }

    private String fmtValue(double value) {
        if (Double.isNaN(value)) {
            return "?";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format("%.2f", value);
    }

    /**
     * calculate 结果展示：整数显示整数，小数最多 4 位并去末尾 0（0.8765 不丢精度，1.50 显示 1.5）。
     * 不复用 fmtValue（其 %.2f 会把 0-1 区间指标舍成两位）
     */
    private String fmtCalcValue(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        String text = String.format("%.4f", value);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * 手册排查步骤数：Markdown 一级~六级标题行数
     */
    private int countHeadings(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : markdown.split("\r?\n")) {
            if (line.matches("#{1,6}\\s+.*")) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argsJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    private static int intOr(Object value, int defaultValue) {
        return value instanceof Number n ? n.intValue() : defaultValue;
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }
}
