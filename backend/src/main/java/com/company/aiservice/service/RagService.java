package com.company.aiservice.service;

import com.company.aiservice.JULOG.JULog;
import com.company.aiservice.JULOG.Tip;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class RagService {

    // 记录问答与检索过程中的异常日志。
    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    // 非流式聊天模型，用于普通问答接口。
    private final ChatLanguageModel chatModel;
    // DeepSeek 官方真流式服务，用于 WebSocket 分段返回答案。
    private final DeepSeekStreamingService deepSeekStreamingService;
    // 向量模型，用于把问题和文档片段转成 embedding。
    private final EmbeddingModel embeddingModel;
    // 保存主向量存储实例，这里默认指向内存向量库。
    private final EmbeddingStore<TextSegment> embeddingStore;
    // 保存 Redis 与内存双存储持有器。
    private final EmbeddingStoresHolder embeddingStoresHolder;
    // 保存知识库本地元信息服务实例。
    private final KnowledgeBaseStoreService knowledgeBaseStoreService;
    // 保存 JULog 日志组件实例。
    private final JULog juLog;

    // 构造器注入聊天模型、DeepSeek 流式服务、向量模型、主向量存储、双存储持有器、知识库元信息服务和日志组件。
    public RagService(ChatLanguageModel chatModel,
                      DeepSeekStreamingService deepSeekStreamingService,
                      EmbeddingModel embeddingModel,
                      EmbeddingStore<TextSegment> embeddingStore,
                      EmbeddingStoresHolder embeddingStoresHolder,
                      KnowledgeBaseStoreService knowledgeBaseStoreService,
                      JULog juLog) {
        // 保存普通聊天模型实例。
        this.chatModel = chatModel;
        // 保存 DeepSeek 官方流式服务实例。
        this.deepSeekStreamingService = deepSeekStreamingService;
        // 保存 embedding 模型实例。
        this.embeddingModel = embeddingModel;
        // 保存主向量存储实例。
        this.embeddingStore = embeddingStore;
        // 保存双存储持有器实例。
        this.embeddingStoresHolder = embeddingStoresHolder;
        // 保存知识库元信息服务实例。
        this.knowledgeBaseStoreService = knowledgeBaseStoreService;
        // 保存 JULog 日志组件实例。
        this.juLog = juLog;
    }

    // 普通同步问答接口，直接返回完整答案。
    public String answer(String question) {
        // 输出当前开始同步问答的日志。
        juLog.write(Tip.MESSAGE, "[RAG问答] 开始同步问答，问题: " + question, true);
        // 先根据问题构造带知识库上下文的提示词。
        String prompt = buildPrompt(question);
        // 输出当前最终发送给大模型的提示词预览日志。
        juLog.write(Tip.MESSAGE, "[RAG问答] 最终 Prompt 预览: " + preview(prompt, 500), true);
        // 调用非流式模型生成完整回答文本。
        String answer = chatModel.generate(prompt);
        // 输出当前同步问答已经完成的日志。
        juLog.write(Tip.MESSAGE, "[RAG问答] 同步问答完成，回答预览: " + preview(answer, 300), true);
        // 直接返回最终文本答案。
        return answer;
    }

    // 流式问答接口，按 token 分段推送给外层调用者。
    public void streamAnswer(String question, Consumer<String> onChunk, Runnable onDone, Consumer<String> onError) {
        // streamAnswer 是 Netty WebSocket 问答的服务入口：先检索拼 Prompt，再调用 DeepSeek SSE，把 token 持续回推给 Netty。
        // 输出当前开始流式问答的日志。
        juLog.write(Tip.MESSAGE, "[RAG问答] 开始流式问答，问题: " + question, true);
        // 先构造带检索上下文的提示词。
        String prompt = buildPrompt(question);
        // 输出当前最终发送给流式模型的提示词预览日志。
        juLog.write(Tip.MESSAGE, "[RAG问答] 流式 Prompt 预览: " + preview(prompt, 500), true);
        // 通过 DeepSeek 官方流式接口执行真流式问答。
        deepSeekStreamingService.streamChat(
                prompt,
                // 每收到一个分段就先输出日志，再回调给上层。
                chunk -> {
                    // 输出当前流式分段预览日志。
                    juLog.write(Tip.MESSAGE, "[RAG问答] 收到流式分段: " + preview(chunk, 120), true);
                    // 把分段内容继续推给上层调用者。
                    onChunk.accept(chunk);
                },
                // 当流式结束时先输出日志，再通知上层完成。
                () -> {
                    // 输出当前流式问答结束日志。
                    juLog.write(Tip.MESSAGE, "[RAG问答] 流式问答完成", true);
                    // 通知上层当前流式已结束。
                    onDone.run();
                },
                // 当流式失败时先输出日志，再通知上层失败。
                error -> {
                    // 输出当前流式问答失败日志。
                    juLog.write(Tip.ERROR, "[RAG问答] 流式问答失败: " + error, true);
                    // 通知上层当前流式执行失败。
                    onError.accept(error);
                }
        );
    }

    // TODO:NOTICE 检索并构建上下文 【根据用户问题拼接 RAG 提示词】
    private String buildPrompt(String question) {
                try {
            // buildPrompt 是 RAG 检索主链路：问题向量化 -> Redis 检索 -> Memory 回退 -> 拼上下文 -> 生成最终 Prompt。
            // 输出当前开始执行知识库检索的日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] 开始查询知识库，问题: " + question, true);
            // 输出当前运行时主向量存储实现类日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] 当前主 EmbeddingStore 实现: " + embeddingStore.getClass().getName(), true);
            // 输出当前 Redis 向量存储实现类日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] 当前 Redis EmbeddingStore 实现: " + embeddingStoresHolder.redisStore().getClass().getName(), true);
            // 输出当前内存向量存储实现类日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] 当前 Memory EmbeddingStore 实现: " + embeddingStoresHolder.memoryStore().getClass().getName(), true);
            // 先读取知识库本地元信息，方便输出当前有哪些历史文档。
            Map<String, Object> stats = knowledgeBaseStoreService.stats();
            // 读取历史文件列表。
            Object filesObject = stats.get("files");
            // 如果历史文件列表是集合，则逐个输出文件名。
            if (filesObject instanceof List<?> files) {
                // 输出当前历史文件总数日志。
                juLog.write(Tip.MESSAGE, "[RAG检索] 当前已登记文档数: " + files.size(), true);
                // 遍历当前文件列表并输出每个文件名和片段数。
                for (Object item : files) {
                    // 如果当前条目是 Map，则读取其中的文件信息。
                    if (item instanceof Map<?, ?> fileMap) {
                        // 输出当前文件名和片段统计日志。
                        juLog.write(Tip.MESSAGE,
                                "[RAG检索] 文档: " + fileMap.get("originalFilename")
                                        + "，已切片数: " + fileMap.get("segmentsIndexed")
                                        + "，落盘路径: " + fileMap.get("storedPath"),
                                true);
                    }
                }
            }



            // 先把用户问题转成向量。
        Embedding qEmbedding = embeddingModel.embed(question).content();
            // 输出当前问题已经完成向量化的日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] 问题向量化完成，开始分别查询 Redis 与 Memory", true);
            // TODO:NOTICE 先用 Redis 向量库执行相似检索。
            List<EmbeddingMatch<TextSegment>> redisMatches = embeddingStoresHolder.redisStore().findRelevant(qEmbedding, 5);
            // 输出 Redis 命中数量日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] Redis 命中片段数: " + redisMatches.size(), true);


            // 再用内存向量库执行相似检索。
            List<EmbeddingMatch<TextSegment>> memoryMatches = embeddingStoresHolder.memoryStore().findRelevant(qEmbedding, 5);
            // 输出内存命中数量日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] Memory 命中片段数: " + memoryMatches.size(), true);


            // 优先采用 Redis 命中结果；如果 Redis 没命中，再回退到内存命中结果。
            List<EmbeddingMatch<TextSegment>> matches = !redisMatches.isEmpty() ? redisMatches : memoryMatches;


            // 逐个输出最终采用的命中片段信息。
            for (int i = 0; i < matches.size(); i++) {
                // 读取当前命中的匹配对象。
                EmbeddingMatch<TextSegment> match = matches.get(i);
                // 读取当前片段文本内容。
                String matchedText = match.embedded() == null ? "" : match.embedded().text();
                // 输出当前命中片段的相似度和文本预览。
                juLog.write(Tip.MESSAGE,
                        "[RAG检索] 最终采用第 " + (i + 1) + " 片，相似度: " + match.score() + "，预览: " + preview(matchedText, 200),
                        true);
            }
            // 把检索到的文本片段拼成上下文。
        String context = matches.stream()
                .map(m -> m.embedded().text())
                .collect(Collectors.joining("\n\n"));



            // 如果知识库里没有可用片段，就返回一个无知识引用的提示词。
            if (context.isBlank()) {
                // 输出当前没有命中任何知识片段的日志。
                juLog.write(Tip.WARRING, "[RAG检索] Redis 与 Memory 都没有命中任何知识库片段", true);
                // 返回无知识库命中的提示词。
                return "你是企业知识库助手。当前知识库中还没有可用的检索片段。"
                        + "\n\n[问题]\n" + question
                        + "\n\n请使用中文回答，并明确说明当前回答未引用到知识库内容。";
            }



            // 输出当前上下文拼接结果预览日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] 拼接后的上下文预览: " + preview(context, 500), true);
            // 如果检索成功，就把上下文与问题一起交给大模型。
        return "你是企业知识库助手。结合下列检索到的上下文回答用户问题。"
                + "\n\n[上下文]\n" + context
                + "\n\n[问题]\n" + question
                + "\n\n请使用中文回答，并在无法从上下文中得出答案时明确说明。";
        } catch (Exception e) {
            // 如果 embedding 或检索失败，则降级成普通问答，避免整个接口崩掉。
            log.warn("知识库检索失败，已降级为普通问答: {}", e.getMessage());
            // 输出当前知识库检索失败的 JULog 日志。
            juLog.write(Tip.ERROR, "[RAG检索] 知识库检索失败，已降级为普通问答: " + e.getMessage(), true);
            // 返回不依赖知识库的降级提示词。
            return "你是企业知识库助手。当前知识库检索暂时不可用，请直接基于通用能力回答用户问题。"
                    + "\n\n[问题]\n" + question
                    + "\n\n请使用中文回答，并说明当前未使用知识库检索结果。";
        }
    }

    // 截断日志预览文本，避免日志内容过长。
    private String preview(String text, int maxLength) {
        // 如果文本为空，则直接返回空字符串。
        if (text == null || text.isBlank()) {
            // 返回空字符串作为日志预览。
            return "";
        }
        // 先把换行替换为空格，避免日志被打断。
        String normalized = text.replace("\n", " ").replace("\r", " ");
        // 如果文本长度没有超限，则直接返回。
        if (normalized.length() <= maxLength) {
            // 返回完整预览文本。
            return normalized;
        }
        // 如果文本过长，则截断后追加省略号。
        return normalized.substring(0, maxLength) + "...";
    }
}
