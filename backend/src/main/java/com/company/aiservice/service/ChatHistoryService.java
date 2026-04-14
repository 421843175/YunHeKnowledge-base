package com.company.aiservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatHistoryService {

    private static final int MAX_HISTORY_SIZE = 100;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ChatHistoryService(StringRedisTemplate redis) {
        this.redis = redis;
        this.objectMapper = new ObjectMapper();
    }

    public void append(int enterpriseId, int userId, String question, String answer) {
        try {
            List<Map<String, Object>> history = readHistory(enterpriseId, userId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", UUID.randomUUID().toString());
            item.put("question", question);
            item.put("answer", answer);
            item.put("createdAt", LocalDateTime.now().toString());
            history.add(0, item);
            if (history.size() > MAX_HISTORY_SIZE) {
                history = new ArrayList<>(history.subList(0, MAX_HISTORY_SIZE));
            }
            redis.opsForValue().set(buildKey(enterpriseId, userId), toJson(history));
        } catch (Exception ignored) {
            // 历史记录写入失败不影响问答主流程。
        }
    }

    public List<Map<String, Object>> list(int enterpriseId, int userId, String keyword) {
        try {
            List<Map<String, Object>> history = readHistory(enterpriseId, userId);
            String q = keyword == null ? "" : keyword.trim();
            if (q.isBlank()) {
                return history;
            }
            String keywordLower = q.toLowerCase();
            return history.stream()
                    .filter(item -> String.valueOf(item.getOrDefault("question", "")).toLowerCase().contains(keywordLower)
                            || String.valueOf(item.getOrDefault("answer", "")).toLowerCase().contains(keywordLower))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> readHistory(int enterpriseId, int userId) throws JsonProcessingException {
        String json = redis.opsForValue().get(buildKey(enterpriseId, userId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> data = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        return data == null ? new ArrayList<>() : new ArrayList<>(data);
    }

    private String toJson(List<Map<String, Object>> history) throws JsonProcessingException {
        return objectMapper.writeValueAsString(history);
    }

    private String buildKey(int enterpriseId, int userId) {
        return "chat:history:enterprise:" + enterpriseId + ":user:" + userId;
    }
}

