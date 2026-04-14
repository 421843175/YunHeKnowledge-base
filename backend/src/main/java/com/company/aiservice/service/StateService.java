package com.company.aiservice.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class StateService {

    private static final String SEGMENTS_TOTAL_KEY_PREFIX = "docs:segments:total:enterprise:";
    private final StringRedisTemplate redis;

    public StateService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void addSegments(int enterpriseId, int count) {
        try {
            redis.opsForValue().increment(buildSegmentsKey(enterpriseId), count);
        } catch (Exception e) {
            // 如果 Redis 写入失败，则直接忽略，避免上传接口整体失败。
        }
    }

    public void subtractSegments(int enterpriseId, int count) {
        if (count <= 0) {
            return;
        }
        try {
            redis.opsForValue().increment(buildSegmentsKey(enterpriseId), -count);
        } catch (Exception e) {
            // 如果 Redis 写入失败，则直接忽略，避免删除接口整体失败。
        }
    }

    public void resetSegments(int enterpriseId, long count) {
        try {
            redis.opsForValue().set(buildSegmentsKey(enterpriseId), String.valueOf(Math.max(count, 0)));
        } catch (Exception e) {
            // 如果 Redis 写入失败，则直接忽略，避免启动预热失败。
        }
    }

    public long totalSegments(int enterpriseId) {
        try {
            String v = redis.opsForValue().get(buildSegmentsKey(enterpriseId));
            if (v == null) {
                return 0L;
            }
            return Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String buildSegmentsKey(int enterpriseId) {
        return SEGMENTS_TOTAL_KEY_PREFIX + enterpriseId;
    }
}
