package com.company.aiservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class DeepSeekStreamingService {

    // 记录 DeepSeek 流式调用过程中的日志。
    private static final Logger log = LoggerFactory.getLogger(DeepSeekStreamingService.class);
    // 定义 JSON 请求体的媒体类型。
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // 保存 DeepSeek 的 API Key。
    private final String apiKey;
    // 保存 DeepSeek 的基础地址。
    private final String baseUrl;
    // 保存 DeepSeek 的聊天模型名。
    private final String chatModelName;
    // 保存 JSON 解析器实例。
    private final ObjectMapper objectMapper;
    // 保存 OkHttp 客户端实例。
    private final OkHttpClient okHttpClient;

    // 通过构造器注入配置并初始化 HTTP 客户端。
    public DeepSeekStreamingService(@Value("${app.deepseek.api-key:}") String apiKey,
                                    @Value("${app.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                                    @Value("${app.deepseek.chat-model:deepseek-chat}") String chatModelName) {
        // 保存 API Key。
        this.apiKey = apiKey;
        // 保存基础地址。
        this.baseUrl = baseUrl;
        // 保存聊天模型名。
        this.chatModelName = chatModelName;
        // 创建 JSON 解析器。
        this.objectMapper = new ObjectMapper();
        // 创建支持长连接的 HTTP 客户端。
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build();
    }

    // 调用 DeepSeek 官方流式接口，并把 token 逐段回调出去。
    public void streamChat(String prompt, Consumer<String> onChunk, Runnable onDone, Consumer<String> onError) {
        // 这里不是 Spring WebFlux 流，而是通过 OkHttp SSE 直连 DeepSeek 官方流式接口，再把每个增量 token 回调出去。
        try {
            // 使用原子变量标记本次流式是否已经正常完成。
            AtomicBoolean completed = new AtomicBoolean(false);
            // 构造 DeepSeek 聊天接口地址。
            String url = baseUrl + "/chat/completions";
            // 构造发送给 DeepSeek 的 JSON 请求体。
            String requestJson = objectMapper.createObjectNode()
                    .put("model", chatModelName)
                    .put("stream", true)
                    .set("messages", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("role", "user")
                                    .put("content", prompt)))
                    .toString();

            // 创建 HTTP 请求对象。
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestJson, JSON))
                    .build();

            // 通过 OkHttp 的 SSE 工厂创建流式事件源。
            EventSource.Factory factory = EventSources.createFactory(okHttpClient);
            // 发起真正的流式请求。
            factory.newEventSource(request, new EventSourceListener() {
                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    // 连接建立成功时记录调试日志。
                    log.info("DeepSeek 流式连接已建立");
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    // DeepSeek 用 [DONE] 表示流式结束。
                    if ("[DONE]".equals(data)) {
                        // 标记当前流式已经正常完成。
                        completed.set(true);
                        // 收到结束标记后通知上层完成。
                        onDone.run();
                        // 主动关闭事件源释放资源，这属于正常收尾。
                        eventSource.cancel();
                        // 当前事件处理结束。
                        return;
                    }

                    try {
                        // 把每一段 SSE 数据解析成 JSON。
                        JsonNode root = objectMapper.readTree(data);
                        // 读取 choices 节点。
                        JsonNode choices = root.path("choices");
                        // 如果没有有效 choices，就忽略当前分片。
                        if (!choices.isArray() || choices.isEmpty()) {
                            // 当前分片没有有效内容，直接返回。
                            return;
                        }
                        // 读取第一项 choice 的 delta 内容。
                        JsonNode delta = choices.get(0).path("delta");
                        // 提取本次返回的文本 token。
                        String content = delta.path("content").asText("");
                        // 如果本次确实有文本内容，就推给上层。
                        if (!content.isEmpty()) {
                            // 把 token 立即转发给上层调用者。
                            onChunk.accept(content);
                        }
                    } catch (Exception e) {
                        // 解析分片失败时通知上层错误。
                        onError.accept("解析 DeepSeek 流式响应失败: " + e.getMessage());
                        // 解析失败后关闭连接，避免脏流继续处理。
                        eventSource.cancel();
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    // 连接自然关闭时记录日志。
                    log.info("DeepSeek 流式连接已关闭");
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    // 如果已经正常完成后又收到 CANCEL，则把它当作正常结束而不是失败。
                    if (completed.get()) {
                        // 记录这是流式收尾阶段的正常取消事件。
                        log.info("DeepSeek 流式连接已正常结束，忽略后续取消信号");
                        // 直接返回，不再上报错误。
                        return;
                    }
                    // 读取 HTTP 状态码文本，便于定位失败原因。
                    String detail = response == null ? "无响应体" : ("HTTP " + response.code());
                    // 记录失败日志。
                    log.error("DeepSeek 流式调用失败: {}", detail, t);
                    // 把错误信息返回给上层。
                    onError.accept(t == null ? ("DeepSeek 流式调用失败: " + detail) : ("DeepSeek 流式调用失败: " + t.getMessage()));
                    // 失败后关闭事件源。
                    if (eventSource != null) {
                        // 主动取消事件源，防止连接继续占用资源。
                        eventSource.cancel();
                    }
                }
            });
        } catch (Exception e) {
            // 如果请求构造阶段就失败，则直接通知上层错误。
            onError.accept("构造 DeepSeek 流式请求失败: " + e.getMessage());
        }
    }
}
