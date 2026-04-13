package com.company.aiservice.service;

import com.company.aiservice.JULOG.JULog;
import com.company.aiservice.JULOG.Tip;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class DocumentService {

    // 创建 Tika 文档解析器，用于解析 pdf、docx、txt 等文件。
    private final ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
    // 保存文档切分器实例。
    private final DocumentSplitter splitter;
    // 保存 embedding 模型实例。
    private final EmbeddingModel embeddingModel;
    // 保存主向量存储实例，这里默认指向内存向量库。
    private final EmbeddingStore<TextSegment> embeddingStore;
    // 保存 Redis 与内存双存储持有器。
    private final EmbeddingStoresHolder embeddingStoresHolder;
    // 保存知识库本地文件与元信息存储服务。
    private final KnowledgeBaseStoreService knowledgeBaseStoreService;
    // 保存 JULog 日志组件实例。
    private final JULog juLog;

    // 通过构造器注入切分器、向量模型、主向量存储、双存储持有器、知识库本地存储服务和日志组件。
    public DocumentService(DocumentSplitter splitter,
                           EmbeddingModel embeddingModel,
                           EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingStoresHolder embeddingStoresHolder,
                           KnowledgeBaseStoreService knowledgeBaseStoreService,
                           JULog juLog) {
        // 保存文档切分器引用。
        this.splitter = splitter;
        // 保存 embedding 模型引用。
        this.embeddingModel = embeddingModel;
        // 保存主向量存储引用。
        this.embeddingStore = embeddingStore;
        // 保存双存储持有器引用。
        this.embeddingStoresHolder = embeddingStoresHolder;
        // 保存知识库本地存储服务引用。
        this.knowledgeBaseStoreService = knowledgeBaseStoreService;
        // 保存 JULog 日志组件引用。
        this.juLog = juLog;
    }

    // 上传并索引单个文件，同时返回该文件的详细入库结果。
    public Map<String, Object> indexFile(MultipartFile file) throws IOException {
        // TODO:NOTICE indexFile 是单文件处理主流程：读字节 -> 识别/解析 -> 切片 -> embedding -> Redis/Memory 双写 -> 本地元信息登记。
        // 先输出当前开始处理上传文件的日志。
        juLog.write(Tip.MESSAGE, "[知识库上传] 开始处理文件: " + file.getOriginalFilename(), true);
        // 输出当前运行时主向量存储实现类日志。
        juLog.write(Tip.MESSAGE, "[知识库上传] 当前主 EmbeddingStore 实现: " + embeddingStore.getClass().getName(), true);
        // 输出当前 Redis 向量存储实现类日志。
        juLog.write(Tip.MESSAGE, "[知识库上传] 当前 Redis EmbeddingStore 实现: " + embeddingStoresHolder.redisStore().getClass().getName(), true);
        // 输出当前内存向量存储实现类日志。
        juLog.write(Tip.MESSAGE, "[知识库上传] 当前 Memory EmbeddingStore 实现: " + embeddingStoresHolder.memoryStore().getClass().getName(), true);
        // 先把上传文件内容完整读入内存，后面解析和落盘都会复用这份字节数组。
        byte[] bytes = file.getBytes();
        // 输出当前文件字节大小，方便确认是否读到真实文件内容。
        juLog.write(Tip.MESSAGE, "[知识库上传] 文件字节大小: " + bytes.length, true);
        // 基于上传文件内容解析出统一文档对象。
        // 文件识别核心就在 parse()：优先交给 Apache Tika 按文件内容/格式解析，失败时再按 UTF-8 纯文本兜底。
        Document document = parse(file, bytes);

        //TODO:NOTICE 开始转向量数据
        // 将文档按切分规则拆成多个文本片段。
        // 这里的 splitter 由 OpenAiConfig 注入，当前采用 recursive(700, 150)，即目标片长约 700、重叠约 150。
        List<TextSegment> segments = splitter.split(document);
        // 输出当前切片总数，方便确认切分是否生效。
        juLog.write(Tip.MESSAGE, "[知识库切片] 文件 " + file.getOriginalFilename() + " 共切出片段数: " + segments.size(), true);

        // 遍历每一个文本片段并写入向量存储。
        for (int i = 0; i < segments.size(); i++) {
            // 取出当前片段对象。
            TextSegment segment = segments.get(i);
            // 读取当前片段文本内容。
            String segmentText = segment.text();
            // 截断当前片段预览文本，避免日志过长。
            String preview = segmentText == null ? "" : segmentText.substring(0, Math.min(segmentText.length(), 120)).replace("\n", " ");
            // 输出当前片段序号和预览内容，方便观察切片方式。
            juLog.write(Tip.MESSAGE, "[知识库切片] 第 " + (i + 1) + " 片长度: " + (segmentText == null ? 0 : segmentText.length()) + "，预览: " + preview, true);
            // TODO:NOTICE 先把文本片段转成 embedding 向量。
            Embedding embedding = embeddingModel.embed(segment).content();
            // 输出当前片段已经完成向量化的日志。
            juLog.write(Tip.MESSAGE, "[向量写入] 第 " + (i + 1) + " 片开始双写向量存储", true);
            // TODO:NOTICE 先把向量和原始片段写入 Redis 向量存储，并记录返回的文档 id。
            String redisDocumentId = embeddingStoresHolder.redisStore().add(embedding, segment);
            // 输出当前 Redis 写入返回的文档 id，方便定位实际写入的 key。
            juLog.write(Tip.MESSAGE, "[向量写入] 第 " + (i + 1) + " 片 Redis 返回文档ID: " + redisDocumentId, true);
            // 再把向量和原始片段写入内存向量存储。
            embeddingStoresHolder.memoryStore().add(embedding, segment);
            // 输出当前片段已经双写完成的日志。
            juLog.write(Tip.MESSAGE, "[向量写入] 第 " + (i + 1) + " 片双写完成", true);
            // 立刻用当前片段自己的向量对 Redis 做一次回查自检。
            try {
                // 执行 Redis 写后即查自检，方便观察 Redis 检索链路是否可用。
                List<EmbeddingMatch<TextSegment>> redisMatches = embeddingStoresHolder.redisStore().findRelevant(embedding, 1);
                // 输出当前片段 Redis 写后即查的命中数日志。
                juLog.write(Tip.MESSAGE, "[向量自检] 第 " + (i + 1) + " 片 Redis 写后即查命中数: " + redisMatches.size(), true);
                // 如果 Redis 写后即查有结果，则输出 Redis 回查结果预览。
                if (!redisMatches.isEmpty() && redisMatches.get(0).embedded() != null) {
                    // 输出当前 Redis 回查结果的相似度和文本预览。
                    juLog.write(Tip.MESSAGE,
                            "[向量自检] 第 " + (i + 1) + " 片 Redis 回查相似度: " + redisMatches.get(0).score()
                                    + "，预览: " + preview(redisMatches.get(0).embedded().text(), 120),
                            true);
                }
            } catch (Exception e) {
                // 如果 Redis 自检失败，则只记录日志，不影响上传主流程。
                juLog.write(Tip.WARRING,
                        "[向量自检] 第 " + (i + 1) + " 片 Redis 写后即查失败，不影响上传: " + e.getMessage(),
                        true);
            }
            // 立刻用当前片段自己的向量对内存库做一次回查自检。
            List<EmbeddingMatch<TextSegment>> memoryMatches = embeddingStoresHolder.memoryStore().findRelevant(embedding, 1);
            // 输出当前片段内存写后即查的命中数日志。
            juLog.write(Tip.MESSAGE, "[向量自检] 第 " + (i + 1) + " 片 Memory 写后即查命中数: " + memoryMatches.size(), true);
            // 如果内存写后即查有结果，则输出内存回查结果预览。
            if (!memoryMatches.isEmpty() && memoryMatches.get(0).embedded() != null) {
                // 输出当前内存回查结果的相似度和文本预览。
                juLog.write(Tip.MESSAGE,
                        "[向量自检] 第 " + (i + 1) + " 片 Memory 回查相似度: " + memoryMatches.get(0).score()
                                + "，预览: " + preview(memoryMatches.get(0).embedded().text(), 120),
                        true);
            }
        }
        // 把原始上传文件落盘，并记录本次索引元信息。
        Map<String, Object> saved = knowledgeBaseStoreService.saveUploadedFile(file.getOriginalFilename(), bytes, segments.size());
        // 输出当前文件已经完成落盘和元信息登记的日志。
        juLog.write(Tip.MESSAGE, "[知识库上传] 文件已落盘，路径: " + saved.get("storedPath"), true);
        // 把本次切分数量补充到返回结果中。
        saved.put("segmentsIndexed", segments.size());
        // 输出当前文件整个上传、切片和向量写入流程结束的日志。
        juLog.write(Tip.MESSAGE, "[知识库上传] 文件处理完成: " + file.getOriginalFilename() + "，总片段数: " + segments.size(), true);
        // 返回当前文件的完整索引结果。
        return saved;
    }

    // 把上传文件解析成统一的 Document 对象。
    private Document parse(MultipartFile file, byte[] bytes) throws IOException {
        // parse 是“文件怎么识别”的关键位置：先拿原始文件名做日志标识，但真正解析依赖 Tika 对文件内容与格式的识别能力。
        // 先取出原始文件名。
        String filename = file.getOriginalFilename();
        // 如果文件名为空，则给一个默认值。
        if (filename == null) {
            // 使用默认文件名避免空值影响后续处理。
            filename = "unknown";
        }
        try (InputStream is = new java.io.ByteArrayInputStream(bytes)) {
            // 输出当前文件开始使用 Tika 解析的日志。
            juLog.write(Tip.MESSAGE, "[知识库解析] 使用 Tika 解析文件: " + filename, true);
            //TODO:NOTCE 解析上传的文档-> 优先使用 Tika 解析结构化文档内容。
            return parser.parse(is);
        } catch (Exception e) {
            // 输出当前文件 Tika 解析失败并准备走纯文本兜底的日志。
            juLog.write(Tip.WARRING, "[知识库解析] Tika 解析失败，改为纯文本兜底: " + filename + "，原因: " + e.getMessage(), true);
            // 如果 Tika 解析失败，则把文件按 UTF-8 文本方式兜底读取。
            String text = new String(bytes, StandardCharsets.UTF_8);
            // 使用纯文本内容构造 Document，避免上传直接失败。
            return Document.from(text);
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
