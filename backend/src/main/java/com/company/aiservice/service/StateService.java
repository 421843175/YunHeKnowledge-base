package com.company.aiservice.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class StateService {

    // 定义 Redis 中保存知识库片段总数的键名。
    private static final String SEGMENTS_TOTAL_KEY = "docs:segments:total";
    // 保存 Redis 模板实例。
    private final StringRedisTemplate redis;

    // 通过构造器注入 Redis 模板。
    public StateService(StringRedisTemplate redis) {
        // 保存 Redis 模板引用。
        this.redis = redis;
    }

    // 将本次新增片段数累加到 Redis 统计中。
    public void addSegments(int count) {
        try {
            // 把 count 叠加到知识库总片段数键上。
        redis.opsForValue().increment(SEGMENTS_TOTAL_KEY, count);
        } catch (Exception e) {
            // 如果 Redis 写入失败，则直接忽略，避免上传接口整体失败。
        }
    }

    // 读取 Redis 中当前累计片段数。
    public long totalSegments() {
        try {
            // 先从 Redis 取出原始字符串值。
        String v = redis.opsForValue().get(SEGMENTS_TOTAL_KEY);
            // 如果 Redis 里没有值，则返回 0。
            if (v == null) {
                // 返回默认值 0，表示当前没有累计记录。
                return 0L;
            }
            // 尝试把字符串转成长整型返回。
            return Long.parseLong(v);
        } catch (Exception e) {
            // 如果 Redis 读取或转换异常，则兜底返回 0。
            return 0L;
        }
    }
}
