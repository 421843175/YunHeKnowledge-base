package com.company.aiservice.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingStoresHolder {

    // 保存 Redis 向量存储实例，用于持久化写入和对照查询。
    private final EmbeddingStore<TextSegment> redisEmbeddingStore;
    // 保存内存向量存储实例，用于对照验证检索是否正常。
    private final EmbeddingStore<TextSegment> memoryEmbeddingStore;

    // 通过构造器注入 Redis 与内存两套向量存储。
    public EmbeddingStoresHolder(@Qualifier("redisEmbeddingStore") EmbeddingStore<TextSegment> redisEmbeddingStore,
                                 @Qualifier("memoryEmbeddingStore") EmbeddingStore<TextSegment> memoryEmbeddingStore) {
        // 保存 Redis 向量存储引用。
        this.redisEmbeddingStore = redisEmbeddingStore;
        // 保存内存向量存储引用。
        this.memoryEmbeddingStore = memoryEmbeddingStore;
    }

    // 返回 Redis 向量存储实例。
    public EmbeddingStore<TextSegment> redisStore() {
        // 将 Redis 向量存储返回给调用方。
        return redisEmbeddingStore;
    }

    // 返回内存向量存储实例。
    public EmbeddingStore<TextSegment> memoryStore() {
        // 将内存向量存储返回给调用方。
        return memoryEmbeddingStore;
    }
}

