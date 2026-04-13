package com.company.aiservice.config;

import com.company.aiservice.JULOG.JULog;
import com.company.aiservice.JULOG.Tip;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class OpenAiConfig {

    // 保存 DeepSeek 的 API Key 配置值。
    @Value("${app.deepseek.api-key:}")
    private String deepseekApiKey;

    // 保存 DeepSeek 的基础地址配置值。
    @Value("${app.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    // 保存 DeepSeek 的聊天模型名配置值。
    @Value("${app.deepseek.chat-model:deepseek-chat}")
    private String deepseekChatModelName;

    // 保存智谱 embedding API Key 配置值。
    @Value("${app.zhipu.api-key:}")
    private String zhipuApiKey;

    // 保存智谱 embedding 基础地址配置值。
    @Value("${app.zhipu.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String zhipuBaseUrl;

    // 保存智谱 embedding 模型名配置值。
    @Value("${app.zhipu.embedding-model:embedding-3}")
    private String zhipuEmbeddingModelName;

    // 保存 Redis 主机配置值。
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    // 保存 Redis 端口配置值。
    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    // 保存 Redis 用户名配置值。
    @Value("${spring.data.redis.username:default}")
    private String redisUsername;

    // 保存 Redis 密码配置值。
    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    // 保存 Redis 向量索引名配置值。
    @Value("${app.redis-vector.index-name:ai_service_embedding_index}")
    private String redisVectorIndexName;

    // 保存 Redis 向量 key 前缀配置值。
    @Value("${app.redis-vector.prefix:embedding:}")
    private String redisVectorPrefix;

    // 保存 embedding 维度配置值。
    @Value("${app.redis-vector.dimension:1024}")
    private int redisVectorDimension;

    // 创建普通非流式聊天模型 Bean。
    @Bean
    public OpenAiChatModel chatModel() {
        // 构建并返回 DeepSeek 聊天模型实例。
        return OpenAiChatModel.builder()
                .apiKey(deepseekApiKey)
                .baseUrl(deepseekBaseUrl)
                .modelName(deepseekChatModelName)
                .build();
    }

    // 创建 embedding 模型 Bean。
    @Bean
    public EmbeddingModel embeddingModel() {
        // 构建并返回智谱 embedding 模型实例。
        return OpenAiEmbeddingModel.builder()
                .apiKey(zhipuApiKey)
                .baseUrl(zhipuBaseUrl)
                .modelName(zhipuEmbeddingModelName)
                .build();
    }

    // 创建 Redis 持久化向量存储 Bean。
    @Bean("redisEmbeddingStore")
    public EmbeddingStore<TextSegment> redisEmbeddingStore(JULog juLog) {
        // 先输出当前 Redis 向量存储初始化配置日志。
        juLog.write(Tip.MESSAGE,
                "[向量存储初始化] host=" + redisHost
                        + "，port=" + redisPort
                        + "，user=" + redisUsername
                        + "，indexName=" + redisVectorIndexName
                        + "，prefix=" + redisVectorPrefix
                        + "，dimension=" + redisVectorDimension,
                true);
        // 这个版本的 RedisEmbeddingStore builder 不支持显式 prefix，因此直接使用构造器传入，确保索引和 key 前缀一致。
        EmbeddingStore<TextSegment> store = new RedisEmbeddingStore(
                redisHost,
                redisPort,
                redisUsername != null && !redisUsername.isBlank() ? redisUsername : null,
                redisPassword != null && !redisPassword.isBlank() ? redisPassword : null,
                redisVectorIndexName,
                redisVectorDimension,
                java.util.List.of()
        );
        // 输出当前 Redis 默认前缀与配置前缀是否一致，方便快速定位命中异常。
        juLog.write(Tip.MESSAGE,
                "[向量存储初始化] langchain4j-redis 0.31.0 默认前缀为 embedding:，当前配置前缀=" + redisVectorPrefix,
                true);
        // 如果当前配置前缀不是该版本固定默认值，则提示当前运行结果仍会按默认前缀工作。
        if (!"embedding:".equals(redisVectorPrefix)) {
            juLog.write(Tip.WARRING,
                    "[向量存储初始化] 当前依赖版本不支持自定义 prefix，Redis 实际仍使用默认前缀 embedding:。如需使用 "
                            + redisVectorPrefix + "，需要升级依赖或自定义 RedisStore 实现。",
                    true);
        }
        // 输出当前真实 Redis 向量存储实现类日志。
        juLog.write(Tip.MESSAGE, "[向量存储初始化] Redis EmbeddingStore 实现: " + store.getClass().getName(), true);
        // 返回 Redis 持久化向量存储实例。
        return store;
    }

    // 创建内存向量存储 Bean，用于对照验证检索链路是否正常。
    @Bean("memoryEmbeddingStore")
    @Primary
    public EmbeddingStore<TextSegment> memoryEmbeddingStore(JULog juLog) {
        // 创建内存向量存储实例。
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        // 输出当前真实内存向量存储实现类日志。
        juLog.write(Tip.MESSAGE, "[向量存储初始化] Memory EmbeddingStore 实现: " + store.getClass().getName(), true);
        // 返回内存向量存储实例作为主检索存储。
        return store;
    }

    // 创建文档切分器 Bean。
    @Bean
    public DocumentSplitter documentSplitter() {
        // 当前切片策略固定为 recursive(700, 150)：单片目标长度约 700 字符，前后重叠约 150，兼顾语义完整性与召回效果。
        // 返回递归切分器实例，用于把长文档拆分成合适片段。
        return DocumentSplitters.recursive(700, 150);
    }
}
