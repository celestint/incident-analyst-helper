package com.lyl.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分析执行配置（app.analysis.*）
 */
@ConfigurationProperties(prefix = "app.analysis")
public class AnalysisProperties {

    /** mock | llm */
    private String executor = "mock";

    /** LLM 工具循环最大迭代次数 */
    private int maxIterations = 12;

    /** RUNNING 超过该分钟数视为分析中断（崩溃恢复扫描用） */
    private int staleMinutes = 10;

    public String getExecutor() {
        return executor;
    }

    public void setExecutor(String executor) {
        this.executor = executor;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getStaleMinutes() {
        return staleMinutes;
    }

    public void setStaleMinutes(int staleMinutes) {
        this.staleMinutes = staleMinutes;
    }
}
