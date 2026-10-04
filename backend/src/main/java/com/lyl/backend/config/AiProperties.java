package com.lyl.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容接口配置（ai.*），可接智谱 GLM / DeepSeek / 百炼兼容模式等。
 * 支持配置多个模型（ai.models 列表），调用时按顺序故障转移：
 * 某个模型限流/异常时自动切下一个，并优先复用最近一次成功的模型。
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 单模型简化配置（兼容旧格式），等价于只有一个元素的 models 列表 */
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 120;

    /** 多模型列表，按顺序故障转移 */
    private List<ModelConfig> models = new ArrayList<>();

    /**
     * 生效的模型列表：models 非空用它；否则把单模型旧配置包装成一个元素
     */
    public List<ModelConfig> effectiveModels() {
        if (models != null && !models.isEmpty()) {
            return models;
        }
        if (model == null || model.isBlank()) {
            return List.of();
        }
        ModelConfig legacy = new ModelConfig();
        legacy.setBaseUrl(baseUrl);
        legacy.setApiKey(apiKey);
        legacy.setModel(model);
        legacy.setTimeoutSeconds(timeoutSeconds);
        return List.of(legacy);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<ModelConfig> getModels() {
        return models;
    }

    public void setModels(List<ModelConfig> models) {
        this.models = models;
    }

    /**
     * 单个模型配置（OpenAI 兼容）
     */
    public static class ModelConfig {

        /** 模型标识名（仅用于日志与错误信息展示） */
        private String name;

        /** OpenAI 兼容 base-url，缺省继承 ai.base-url */
        private String baseUrl;

        /** API Key，缺省继承 ai.api-key */
        private String apiKey;

        /** 模型名，如 glm-4.7 / deepseek-chat */
        private String model;

        /** 单次调用超时（秒），缺省继承 ai.timeout-seconds */
        private Integer timeoutSeconds;

        /**
         * 继承顶层缺省值，返回最终生效配置
         */
        public void inheritFrom(AiProperties parent) {
            if (baseUrl == null || baseUrl.isBlank()) {
                setBaseUrl(parent.getBaseUrl());
            }
            if (apiKey == null || apiKey.isBlank()) {
                setApiKey(parent.getApiKey());
            }
            if (timeoutSeconds == null) {
                setTimeoutSeconds(parent.getTimeoutSeconds());
            }
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
