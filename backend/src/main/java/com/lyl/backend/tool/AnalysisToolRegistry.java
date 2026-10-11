package com.lyl.backend.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyl.backend.service.DataAnalyticsService;
import com.lyl.backend.service.RunbookService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 工具注册表：声明工具清单（名称/描述/JSON Schema 参数）并执行。
 * 只暴露注册表内的工具；执行是纯函数（只读查询），幂等由执行器基于 tool_idempotency 保证。
 */
@Slf4j
@Component
public class AnalysisToolRegistry {

    private final DataAnalyticsService dataAnalyticsService;
    private final RunbookService runbookService;
    private final ExpressionEvaluator expressionEvaluator;
    private final ObjectMapper objectMapper;

    public AnalysisToolRegistry(DataAnalyticsService dataAnalyticsService,
                                RunbookService runbookService,
                                ExpressionEvaluator expressionEvaluator,
                                ObjectMapper objectMapper) {
        this.dataAnalyticsService = dataAnalyticsService;
        this.runbookService = runbookService;
        this.expressionEvaluator = expressionEvaluator;
        this.objectMapper = objectMapper;
    }

    /**
     * 工具清单，顺序即呈现给模型的顺序。getRunbook 排第一，引导模型先取手册
     */
    public List<ToolSpecification> specifications() {
        return List.of(
                ToolSpecification.builder()
                        .name("getRunbook")
                        .description("按告警名关键词匹配排查手册，关键词未命中时返回通用兜底手册。分析第一步先调用它；返回手册后严格按手册步骤执行")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("alertName", "告警名，如 HighMemoryUsage")
                                .required("alertName")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("getLogs")
                        .description("查询服务日志，返回 {total, items}。适合定位错误关键字")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("service", "服务名，缺省为告警服务")
                                .addStringProperty("keyword", "日志内容过滤关键字")
                                .addIntegerProperty("startTime", "开始时间（Unix 秒），缺省为告警前10分钟")
                                .addIntegerProperty("endTime", "结束时间（Unix 秒），缺省为告警后5分钟")
                                .addIntegerProperty("limit", "返回条数上限，默认100")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("countLogs")
                        .description("统计日志条数，只返回数字，适合快速确认错误量级")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("service", "服务名，缺省为告警服务")
                                .addStringProperty("keyword", "日志内容过滤关键字")
                                .addIntegerProperty("startTime", "开始时间（Unix 秒）")
                                .addIntegerProperty("endTime", "结束时间（Unix 秒）")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("getMetrics")
                        .description("查询服务指标（kpi_name + value），返回 {total, items}")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("service", "服务名，缺省为告警服务")
                                .addStringProperty("metricName", "指标名过滤（模糊匹配）")
                                .addIntegerProperty("startTime", "开始时间（Unix 秒）")
                                .addIntegerProperty("endTime", "结束时间（Unix 秒）")
                                .addIntegerProperty("limit", "返回条数上限，默认100")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("getKpi")
                        .description("查询服务 KPI（请求量 rr、成功率 sr、调用次数 cnt、平均响应时间 mrt），返回 {total, items}")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("service", "服务名，缺省为告警服务")
                                .addIntegerProperty("startTime", "开始时间（Unix 秒）")
                                .addIntegerProperty("endTime", "结束时间（Unix 秒）")
                                .addIntegerProperty("limit", "返回条数上限，默认100")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("getSlowSpans")
                        .description("查询慢调用 span（按耗时降序），用于链路定位上游/下游瓶颈。查空只代表未发现慢调用，不代表调用链数据缺失；判定调用链数据缺失用 getTraceCount")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("service", "服务名，缺省为告警服务")
                                .addIntegerProperty("startTime", "开始时间（Unix 秒）")
                                .addIntegerProperty("endTime", "结束时间（Unix 秒）")
                                .addIntegerProperty("limit", "返回条数上限，默认10")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("getTraceCount")
                        .description("统计窗口内调用 span 总数，返回数字。判定\"调用链数据缺失\"必须用它：双窗口 span 总数均为 0 才可认定调用链缺失")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("service", "服务名，缺省为告警服务")
                                .addIntegerProperty("startTime", "开始时间（Unix 秒）")
                                .addIntegerProperty("endTime", "结束时间（Unix 秒）")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("getMemoryUsage")
                        .description("内存类告警专用：按时间点返回内存占比 {time, noCacheMemPerc, memUsedMemPerc}（百分比，后端已按公式算好，按时间升序）。查内存占比直接用它，无需 getMetrics + calculate")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("service", "服务名，缺省为告警服务")
                                .addIntegerProperty("startTime", "开始时间（Unix 秒）")
                                .addIntegerProperty("endTime", "结束时间（Unix 秒）")
                                .addIntegerProperty("limit", "返回时间点数上限，默认100")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("calculate")
                        .description("四则运算计算器，返回 {expression, value}。需要计算指标值/比率/百分比（如内存占比公式）时必须调用它，禁止心算；expression 为纯数学表达式（+ - * / 括号），变量先代入具体数值")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("expression", "纯数学四则表达式，如 (100-30)/(100+50)*100")
                                .required("expression")
                                .build())
                        .build());
    }

    /**
     * 判断是否为注册表内的工具
     */
    public boolean contains(String name) {
        return specifications().stream().anyMatch(t -> t.name().equals(name));
    }

    /**
     * 执行工具并返回 JSON 字符串结果。参数不合法或执行异常抛出 RuntimeException，由执行器转成失败结果回填模型
     */
    public String execute(String name, Map<String, Object> args, ToolContext ctx) {
        Object result = switch (name) {
            case "getRunbook" -> {
                RunbookService.RunbookMatch match = runbookService.findRunbook(str(args.get("alertName")));
                yield match != null ? Map.of("matched", true, "file", match.file(), "content", match.content())
                        : Map.of("matched", false, "message", "手册文件加载失败，请基于告警信息直接输出分析报告");
            }
            case "getLogs" -> dataAnalyticsService.getLogs(
                    ctx.serviceOr(args), str(args.get("keyword")),
                    ctx.startOr(args), ctx.endOr(args), intOr(args.get("limit"), 100));
            case "countLogs" -> dataAnalyticsService.countLogs(
                    ctx.serviceOr(args), str(args.get("keyword")),
                    ctx.startOr(args), ctx.endOr(args));
            case "getMetrics" -> dataAnalyticsService.getMetrics(
                    ctx.serviceOr(args), str(args.get("metricName")),
                    ctx.startOr(args), ctx.endOr(args), intOr(args.get("limit"), 100));
            case "getKpi" -> dataAnalyticsService.getKpi(
                    ctx.serviceOr(args),
                    ctx.startOr(args), ctx.endOr(args), intOr(args.get("limit"), 100));
            case "getSlowSpans" -> dataAnalyticsService.getSlowSpans(
                    ctx.serviceOr(args),
                    ctx.startOr(args), ctx.endOr(args), intOr(args.get("limit"), 10));
            case "getTraceCount" -> dataAnalyticsService.getTraceCount(
                    ctx.serviceOr(args), ctx.startOr(args), ctx.endOr(args));
            case "getMemoryUsage" -> dataAnalyticsService.getMemoryUsage(
                    ctx.serviceOr(args),
                    ctx.startOr(args), ctx.endOr(args), intOr(args.get("limit"), 100));
            // 计算结果必须是对象而非裸数字：裸数字 0 会被执行器判为"全空"误计入止损计数
            case "calculate" -> {
                ExpressionEvaluator.EvalResult calc = expressionEvaluator.eval(str(args.get("expression")));
                yield Map.of("expression", calc.expression(), "value", calc.value());
            }
            default -> throw new IllegalArgumentException("未知工具: " + name);
        };
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("工具结果序列化失败: " + name, e);
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer intOr(Object value, int defaultValue) {
        return value instanceof Number n ? n.intValue() : defaultValue;
    }
}
