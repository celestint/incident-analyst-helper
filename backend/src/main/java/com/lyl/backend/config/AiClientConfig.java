package com.lyl.backend.config;

import com.lyl.backend.service.LlmChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI 兼容模型客户端，仅在 app.analysis.executor=llm 时装配。
 * mock 模式（测试）不装配，避免无 API key 启动失败。
 */
@Configuration
@ConditionalOnProperty(name = "app.analysis.executor", havingValue = "llm")
public class AiClientConfig {

    /**
     * 多模型故障转移客户端（构建失败/配置缺失的模型自动跳过）
     */
    @Bean
    public LlmChatClient llmChatClient(AiProperties properties) {
        return new LlmChatClient(properties);
    }
}
