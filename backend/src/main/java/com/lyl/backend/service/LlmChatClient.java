package com.lyl.backend.service;

import com.lyl.backend.config.AiProperties;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 多模型 LLM 客户端：包装按优先级排列的模型列表，提供故障转移。
 * 调用时从最近一次成功的模型开始按顺序尝试，某模型限流/异常则切下一个；
 * 全部失败时抛出携带各模型错误信息的异常。
 */
@Slf4j
public class LlmChatClient {

    /** 单次调用整体超时（秒），兜底模型端超时配置 */
    private static final long CALL_TIMEOUT_SECONDS = 300;

    private final List<StreamingChatModel> models = new ArrayList<>();
    private final List<String> modelNames = new ArrayList<>();
    /** 最近一次成功的模型下标，下次调用优先从它开始 */
    private int lastIndex = 0;

    /**
     * 按配置顺序构建各模型客户端；构建失败（配置缺失等）的模型跳过并记日志
     */
    public LlmChatClient(AiProperties properties) {
        List<AiProperties.ModelConfig> configs = properties.effectiveModels();
        for (AiProperties.ModelConfig config : configs) {
            config.inheritFrom(properties);
            String name = config.getName() != null ? config.getName() : config.getModel();
            try {
                models.add(OpenAiStreamingChatModel.builder()
                        .baseUrl(config.getBaseUrl())
                        .apiKey(config.getApiKey())
                        .modelName(config.getModel())
                        .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                        .temperature(0.2)
                        .build());
                modelNames.add(name);
                log.info("LLM model registered: {}", name);
            } catch (Exception e) {
                log.warn("LLM model {} skipped (build failed): {}", name, e.getMessage());
            }
        }
        if (models.isEmpty()) {
            log.warn("LlmChatClient initialized with no usable model");
        }
    }

    /**
     * 供测试注入桩模型
     */
    public LlmChatClient(List<StreamingChatModel> stubModels) {
        for (int i = 0; i < stubModels.size(); i++) {
            models.add(stubModels.get(i));
            modelNames.add("stub-" + i);
        }
    }

    /**
     * 发起一轮流式调用，故障转移。onPartial 收到文本增量（可为 null）。
     * 全部模型失败时抛 IllegalStateException，message 汇总各模型错误。
     */
    public ChatResponse chat(ChatRequest request, Consumer<String> onPartial) {
        if (models.isEmpty()) {
            throw new IllegalStateException("没有可用的 LLM 模型，请检查 ai.models 配置");
        }
        List<String> errors = new ArrayList<>();
        int size = models.size();
        for (int i = 0; i < size; i++) {
            int index = Math.floorMod(lastIndex + i, size);
            String name = modelNames.get(index);
            try {
                ChatResponse response = doCall(models.get(index), request, onPartial);
                lastIndex = index;
                return response;
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                errors.add(name + ": " + message);
                log.warn("LLM model {} failed, trying next: {}", name, message);
            }
        }
        throw new IllegalStateException("全部模型调用失败: " + String.join(" | ", errors));
    }

    /**
     * 对单个模型执行流式调用并等待完成
     */
    private ChatResponse doCall(StreamingChatModel model, ChatRequest request, Consumer<String> onPartial)
            throws Exception {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                if (onPartial != null) {
                    onPartial.accept(token);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
