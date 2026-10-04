package com.lyl.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runbook 关键词匹配服务。
 * 读取 mock-data/runbook-keywords.json（文件名 -> 关键词列表），
 * 按告警名（忽略大小写）匹配 runbooks/ 下的手册文件，返回手册全文。
 */
@Slf4j
@Service
public class RunbookService {

    private static final String KEYWORDS_FILE = "mock-data/runbook-keywords.json";
    private static final String RUNBOOK_DIR = "mock-data/runbooks/";

    private final ObjectMapper objectMapper;
    /** 文件名 -> 关键词列表 */
    private Map<String, List<String>> keywordMap = Map.of();

    public RunbookService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时加载 runbook 关键词映射表（文件名 -> 关键词列表），失败时置空不阻塞启动
     */
    @PostConstruct
    void loadKeywords() {
        try {
            keywordMap = objectMapper.readValue(
                    new ClassPathResource(KEYWORDS_FILE).getInputStream(),
                    new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to load runbook keywords: {}", e.getMessage());
        }
    }

    /**
     * 命中的手册：文件名（供前端展示"读取 xxx.md"）+ 全文
     */
    public record RunbookMatch(String file, String content) {
    }

    /**
     * 按告警名匹配手册。命中返回文件名与手册全文；未命中返回 null。
     */
    public RunbookMatch findRunbook(String alertName) {
        if (alertName == null || alertName.isBlank()) {
            return null;
        }
        String lower = alertName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : keywordMap.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    String content = loadRunbookFile(entry.getKey());
                    return content != null ? new RunbookMatch(entry.getKey(), content) : null;
                }
            }
        }
        return null;
    }

    /**
     * 读取手册文件内容，读取失败返回 null
     */
    private String loadRunbookFile(String fileName) {
        try {
            return new String(
                    new ClassPathResource(RUNBOOK_DIR + fileName).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load runbook {}: {}", fileName, e.getMessage());
            return null;
        }
    }
}
