package com.company.aiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EnterpriseVectorStoreService {

    private static final String METADATA_ENTERPRISE_ID = "enterprise_id";

    private final String redisHost;
    private final int redisPort;
    private final String redisUsername;
    private final String redisPassword;
    private final String redisVectorIndexName;
    private final int redisVectorDimension;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Integer, EmbeddingStore<TextSegment>> redisStores = new ConcurrentHashMap<>();
    private final Map<Integer, InMemoryEmbeddingStore<TextSegment>> memoryStores = new ConcurrentHashMap<>();

    public EnterpriseVectorStoreService(@Value("${spring.data.redis.host:localhost}") String redisHost,
                                        @Value("${spring.data.redis.port:6379}") int redisPort,
                                        @Value("${spring.data.redis.username:default}") String redisUsername,
                                        @Value("${spring.data.redis.password:}") String redisPassword,
                                        @Value("${app.redis-vector.index-name:ai_service_embedding_index}") String redisVectorIndexName,
                                        @Value("${app.redis-vector.dimension:1024}") int redisVectorDimension,
                                        StringRedisTemplate stringRedisTemplate) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisUsername = redisUsername;
        this.redisPassword = redisPassword;
        this.redisVectorIndexName = redisVectorIndexName;
        this.redisVectorDimension = redisVectorDimension;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public EmbeddingStore<TextSegment> redisStore(int enterpriseId) {
        return redisStores.computeIfAbsent(enterpriseId, this::createRedisStore);
    }

    public EmbeddingStore<TextSegment> memoryStore(int enterpriseId) {
        return memoryStores.computeIfAbsent(enterpriseId, key -> new InMemoryEmbeddingStore<>());
    }

    public void rebuildMemoryStore(int enterpriseId, List<TextSegment> segments, List<Embedding> embeddings) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        for (int i = 0; i < segments.size(); i++) {
            store.add(embeddings.get(i), segments.get(i));
        }
        memoryStores.put(enterpriseId, store);
    }

    public List<EmbeddingMatch<TextSegment>> findRelevantFromRedis(int enterpriseId, Embedding embedding, int maxResults) {
        EmbeddingStore<TextSegment> store = redisStore(enterpriseId);
        List<EmbeddingMatch<TextSegment>> matches = store.findRelevant(embedding, maxResults);
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        return matches.stream()
                .filter(match -> match != null && match.embedded() != null)
                .filter(match -> belongsToEnterprise(match.embedded(), enterpriseId))
                .toList();
    }

    public int deleteRedisDocuments(int enterpriseId, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return 0;
        }
        String indexName = redisVectorIndexName + "_enterprise_" + enterpriseId;
        int deleted = 0;
        for (String documentId : documentIds) {
            if (documentId == null || documentId.isBlank()) {
                continue;
            }
            String redisKey = "embedding:" + documentId;
            try {
                Boolean removed = stringRedisTemplate.delete(redisKey);
                if (Boolean.TRUE.equals(removed)) {
                    deleted++;
                }
                stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                        connection.execute(
                                "FT.DEL",
                                indexName.getBytes(StandardCharsets.UTF_8),
                                documentId.getBytes(StandardCharsets.UTF_8)
                        )
                );
            } catch (Exception ignored) {
                // 交给上层统一兜底处理
            }
        }
        return deleted;
    }

    public int deleteRedisDocumentsByOriginalFilename(int enterpriseId, String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return 0;
        }
        String indexName = redisVectorIndexName + "_enterprise_" + enterpriseId;
        String query = "@original_filename:{" + escapeTagValue(originalFilename) + "}";
        Set<String> documentIds = new LinkedHashSet<>();

        try {
            Object searchResult = stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                    connection.execute(
                            "FT.SEARCH",
                            indexName.getBytes(StandardCharsets.UTF_8),
                            query.getBytes(StandardCharsets.UTF_8),
                            "RETURN".getBytes(StandardCharsets.UTF_8),
                            "1".getBytes(StandardCharsets.UTF_8),
                            "original_filename".getBytes(StandardCharsets.UTF_8),
                            "LIMIT".getBytes(StandardCharsets.UTF_8),
                            "0".getBytes(StandardCharsets.UTF_8),
                            "10000".getBytes(StandardCharsets.UTF_8)
                    )
            );
            collectDocumentIds(searchResult, documentIds);
        } catch (Exception ignored) {
            // FT.SEARCH 失败时继续走 key 扫描兜底
        }

        int deleted = deleteRedisDocuments(enterpriseId, new ArrayList<>(documentIds));
        if (deleted > 0) {
            return deleted;
        }
        return deleteRedisKeysByOriginalFilename(enterpriseId, originalFilename);
    }

    private boolean belongsToEnterprise(TextSegment segment, int enterpriseId) {
        if (segment == null) {
            return false;
        }
        try {
            String metadataEnterpriseId = segment.metadata() == null ? null : segment.metadata().getString(METADATA_ENTERPRISE_ID);
            if (metadataEnterpriseId != null && !metadataEnterpriseId.isBlank()) {
                return String.valueOf(enterpriseId).equals(metadataEnterpriseId);
            }
        } catch (Exception ignored) {
            // metadata 不可用时继续走文本 key 兜底
        }
        String text = segment.text();
        if (text == null || text.isBlank()) {
            return false;
        }
        String json = readRedisJsonValue(text);
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> data = extractFirstJsonObject(json);
            if (data == null || data.isEmpty()) {
                return false;
            }
            return String.valueOf(enterpriseId).equals(String.valueOf(data.getOrDefault(METADATA_ENTERPRISE_ID, "")));
        } catch (Exception ignored) {
            return false;
        }
    }

    private int deleteRedisKeysByOriginalFilename(int enterpriseId, String originalFilename) {
        Set<String> keys = stringRedisTemplate.keys("embedding:*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }

        int deleted = 0;
        for (String key : keys) {
            try {
                String json = readRedisJsonValue(key);
                if (json == null || json.isBlank()) {
                    continue;
                }
                Map<String, Object> data = extractFirstJsonObject(json);
                if (data == null || data.isEmpty()) {
                    continue;
                }
                String currentEnterpriseId = String.valueOf(data.getOrDefault("enterprise_id", ""));
                String currentOriginalFilename = String.valueOf(data.getOrDefault("original_filename", ""));
                if (String.valueOf(enterpriseId).equals(currentEnterpriseId) && originalFilename.equals(currentOriginalFilename)) {
                    Boolean removed = stringRedisTemplate.delete(key);
                    if (Boolean.TRUE.equals(removed)) {
                        deleted++;
                    }
                }
            } catch (Exception ignored) {
                // 单个 key 解析失败不影响其他 key 清理
            }
        }
        return deleted;
    }

    private String readRedisJsonValue(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null && !json.isBlank()) {
            return json;
        }
        Object jsonResult = stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                connection.execute(
                        "JSON.GET",
                        key.getBytes(StandardCharsets.UTF_8),
                        "$.".getBytes(StandardCharsets.UTF_8)
                )
        );
        if (jsonResult instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return jsonResult == null ? null : String.valueOf(jsonResult);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFirstJsonObject(String json) throws Exception {
        Object parsed = objectMapper.readValue(json, Object.class);
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (parsed instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return (Map<String, Object>) first;
        }
        return null;
    }

    private void collectDocumentIds(Object searchResult, Set<String> documentIds) {
        if (!(searchResult instanceof List<?> items) || items.size() < 2) {
            return;
        }
        for (int i = 1; i < items.size(); i += 2) {
            Object item = items.get(i);
            if (item instanceof byte[] bytes) {
                documentIds.add(new String(bytes, StandardCharsets.UTF_8));
            } else if (item != null) {
                documentIds.add(String.valueOf(item));
            }
        }
    }

    private String escapeTagValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("-", "\\-")
                .replace(".", "\\.")
                .replace(" ", "\\ ")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("<", "\\<")
                .replace(">", "\\>")
                .replace(":", "\\:")
                .replace(";", "\\;")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace("@", "\\@");
    }

    private EmbeddingStore<TextSegment> createRedisStore(int enterpriseId) {
        return new RedisEmbeddingStore(
                redisHost,
                redisPort,
                redisUsername != null && !redisUsername.isBlank() ? redisUsername : null,
                redisPassword != null && !redisPassword.isBlank() ? redisPassword : null,
                redisVectorIndexName + "_enterprise_" + enterpriseId,
                redisVectorDimension,
                java.util.List.of()
        );
    }
}

