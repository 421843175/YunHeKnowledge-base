package com.company.aiservice.service;

import com.company.aiservice.JULOG.JULog;
import com.company.aiservice.JULOG.Tip;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final ChatLanguageModel chatModel;
    private final DeepSeekStreamingService deepSeekStreamingService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EnterpriseVectorStoreService enterpriseVectorStoreService;
    private final KnowledgeBaseStoreService knowledgeBaseStoreService;
    private final JULog juLog;

    public RagService(ChatLanguageModel chatModel,
                      DeepSeekStreamingService deepSeekStreamingService,
                      EmbeddingModel embeddingModel,
                      @Qualifier("memoryEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore,
                      EnterpriseVectorStoreService enterpriseVectorStoreService,
                      KnowledgeBaseStoreService knowledgeBaseStoreService,
                      JULog juLog) {
        this.chatModel = chatModel;
        this.deepSeekStreamingService = deepSeekStreamingService;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.enterpriseVectorStoreService = enterpriseVectorStoreService;
        this.knowledgeBaseStoreService = knowledgeBaseStoreService;
        this.juLog = juLog;
    }

    public String answer(int enterpriseId, String question) {
        juLog.write(Tip.MESSAGE, "[RAG问答] 企业 " + enterpriseId + " 开始同步问答，问题: " + question, true);
        String prompt = buildPrompt(enterpriseId, question);
        juLog.write(Tip.MESSAGE, "[RAG问答] 最终 Prompt 预览: " + preview(prompt, 500), true);
        String answer = chatModel.generate(prompt);
        juLog.write(Tip.MESSAGE, "[RAG问答] 同步问答完成，回答预览: " + preview(answer, 300), true);
        return answer;
    }

    public void streamAnswer(int enterpriseId, String question, Consumer<String> onChunk, Runnable onDone, Consumer<String> onError) {
        juLog.write(Tip.MESSAGE, "[RAG问答] 企业 " + enterpriseId + " 开始流式问答，问题: " + question, true);
        String prompt = buildPrompt(enterpriseId, question);
        juLog.write(Tip.MESSAGE, "[RAG问答] 流式 Prompt 预览: " + preview(prompt, 500), true);
        deepSeekStreamingService.streamChat(
                prompt,
                chunk -> {
                    juLog.write(Tip.MESSAGE, "[RAG问答] 收到流式分段: " + preview(chunk, 120), true);
                    onChunk.accept(chunk);
                },
                () -> {
                    juLog.write(Tip.MESSAGE, "[RAG问答] 流式问答完成", true);
                    onDone.run();
                },
                error -> {
                    juLog.write(Tip.ERROR, "[RAG问答] 流式问答失败: " + error, true);
                    onError.accept(error);
                }
        );
    }

    private String buildPrompt(int enterpriseId, String question) {
        try {
            juLog.write(Tip.MESSAGE, "[RAG检索] 企业 " + enterpriseId + " 开始查询知识库，问题: " + question, true);
            juLog.write(Tip.MESSAGE, "[RAG检索] 当前主 EmbeddingStore 实现: " + embeddingStore.getClass().getName(), true);
            juLog.write(Tip.MESSAGE, "[RAG检索] 当前企业 Redis EmbeddingStore 实现: " + enterpriseVectorStoreService.redisStore(enterpriseId).getClass().getName(), true);
            juLog.write(Tip.MESSAGE, "[RAG检索] 当前企业 Memory EmbeddingStore 实现: " + enterpriseVectorStoreService.memoryStore(enterpriseId).getClass().getName(), true);
            Map<String, Object> stats = knowledgeBaseStoreService.stats(enterpriseId);
            Object filesObject = stats.get("files");
            if (filesObject instanceof List<?> files) {
                juLog.write(Tip.MESSAGE, "[RAG检索] 企业 " + enterpriseId + " 当前已登记文档数: " + files.size(), true);
                for (Object item : files) {
                    if (item instanceof Map<?, ?> fileMap) {
                        juLog.write(Tip.MESSAGE,
                                "[RAG检索] 文档: " + fileMap.get("originalFilename")
                                        + "，已切片数: " + fileMap.get("segmentsIndexed")
                                        + "，落盘路径: " + fileMap.get("storedPath"),
                                true);
                    }
                }
            }

            Embedding qEmbedding = embeddingModel.embed(question).content();
            juLog.write(Tip.MESSAGE, "[RAG检索] 问题向量化完成，开始分别查询企业 Redis 与企业 Memory", true);
            EmbeddingStore<TextSegment> redisStore = enterpriseVectorStoreService.redisStore(enterpriseId);
            EmbeddingStore<TextSegment> memoryStore = enterpriseVectorStoreService.memoryStore(enterpriseId);
            List<EmbeddingMatch<TextSegment>> redisMatches = enterpriseVectorStoreService.findRelevantFromRedis(enterpriseId, qEmbedding, 10);
            juLog.write(Tip.MESSAGE, "[RAG检索] 企业 " + enterpriseId + " Redis 命中片段数: " + redisMatches.size(), true);
            List<EmbeddingMatch<TextSegment>> memoryMatches = safeFindRelevant(memoryStore, qEmbedding, 10);
            juLog.write(Tip.MESSAGE, "[RAG检索] 企业 " + enterpriseId + " Memory 命中片段数: " + memoryMatches.size(), true);
            List<EmbeddingMatch<TextSegment>> matches = !redisMatches.isEmpty() ? redisMatches : memoryMatches;

            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                String matchedText = match.embedded() == null ? "" : match.embedded().text();
                juLog.write(Tip.MESSAGE,
                        "[RAG检索] 企业 " + enterpriseId + " 最终采用第 " + (i + 1) + " 片，相似度: " + match.score() + "，预览: " + preview(matchedText, 200),
                        true);
            }
            String context = matches.stream()
                    .map(EmbeddingMatch::embedded)
                    .filter(java.util.Objects::nonNull)
                    .map(TextSegment::text)
                    .collect(Collectors.joining("\n\n"));

            if (context.isBlank()) {
                juLog.write(Tip.WARRING, "[RAG检索] 企业 " + enterpriseId + " Redis 与 Memory 都没有命中任何本企业知识库片段", true);
                return "你是企业知识库助手。当前企业知识库中还没有可用的检索片段。"
                        + "\n\n[问题]\n" + question
                        + "\n\n请使用中文回答，并明确说明当前回答未引用到该企业知识库内容。";
            }

            juLog.write(Tip.MESSAGE, "[RAG检索] 拼接后的上下文预览: " + preview(context, 500), true);
            return "你是企业知识库助手。结合下列检索到的当前企业上下文回答用户问题。"
                    + "\n\n[上下文]\n" + context
                    + "\n\n[问题]\n" + question
                    + "\n\n请使用中文回答，并在无法从上下文中得出答案时明确说明。";
        } catch (Exception e) {
            log.warn("知识库检索失败，已降级为普通问答: {}", e.getMessage());
            juLog.write(Tip.ERROR, "[RAG检索] 企业知识库检索失败，已降级为普通问答: " + e.getMessage(), true);
            return "你是企业知识库助手。当前企业知识库检索暂时不可用，请直接基于通用能力回答用户问题。"
                    + "\n\n[问题]\n" + question
                    + "\n\n请使用中文回答，并说明当前未使用企业知识库检索结果。";
        }
    }

    private List<EmbeddingMatch<TextSegment>> safeFindRelevant(EmbeddingStore<TextSegment> store, Embedding embedding, int maxResults) {
        List<EmbeddingMatch<TextSegment>> matches = store.findRelevant(embedding, maxResults);
        return matches == null ? List.of() : matches.stream()
                .filter(match -> match != null && match.embedded() != null)
                .toList();
    }

    private String preview(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\n", " ").replace("\r", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
