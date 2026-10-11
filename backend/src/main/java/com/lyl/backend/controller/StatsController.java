package com.lyl.backend.controller;

import com.lyl.backend.model.ApiResponse;
import com.lyl.backend.service.EvaluationStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 评测指标统计（只读），口径见 docs/plan/EVALUATION_PLAN.md。
 * 范围过滤（默认只算正式数据，即 tag 为空）：
 * - ?tag=test 单看某场景标签
 * - ?scope=all 全量（与 tag 互斥）
 * - ?since=yyyy-MM-dd 按创建时间收窄
 * 历史污染数据（限流模型测试期）不删除，打标后从正式口径排除。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final EvaluationStatsService statsService;

    public StatsController(EvaluationStatsService statsService) {
        this.statsService = statsService;
    }

    /** 汇总指标：完成率/耗时/工具调用分布/失控率/FAILED 归因/工具成功率/采纳率 */
    @GetMapping("/evaluation")
    public ApiResponse<Map<String, Object>> evaluation(
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "scope", required = false) String scope) {
        return ApiResponse.ok(statsService.evaluation(since, tag, scope));
    }

    /**
     * 按指标下钻明细：metric = failed / slow / toolCalls / runaway / dislike。
     * toolCalls 需带 bucket（1-3/4-6/7-9/10+）；slow 可带 limit（默认 5）。
     */
    @GetMapping("/evaluation/incidents")
    public ApiResponse<List<Map<String, Object>>> evaluationIncidents(
            @RequestParam(name = "metric") String metric,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return ApiResponse.ok(statsService.detailIncidents(metric, bucket, since, tag, scope, limit));
    }
}
