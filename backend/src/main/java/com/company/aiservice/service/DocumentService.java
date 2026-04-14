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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DocumentService {

    private static final String METADATA_ENTERPRISE_ID = "enterprise_id";
    private static final String METADATA_FILE_NAME = "original_filename";

    private final ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
    private final DocumentSplitter splitter;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EnterpriseVectorStoreService enterpriseVectorStoreService;
    private final KnowledgeBaseStoreService knowledgeBaseStoreService;
    private final JULog juLog;

    public DocumentService(DocumentSplitter splitter,
                           EmbeddingModel embeddingModel,
                           @Qualifier("memoryEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore,
                           EnterpriseVectorStoreService enterpriseVectorStoreService,
                           KnowledgeBaseStoreService knowledgeBaseStoreService,
                           JULog juLog) {
        this.splitter = splitter;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.enterpriseVectorStoreService = enterpriseVectorStoreService;
        this.knowledgeBaseStoreService = knowledgeBaseStoreService;
        this.juLog = juLog;
    }

    public Map<String, Object> indexFile(int enterpriseId, MultipartFile file) throws IOException {
        juLog.write(Tip.MESSAGE, "[知识库上传] 企业 " + enterpriseId + " 开始处理文件: " + file.getOriginalFilename(), true);
        juLog.write(Tip.MESSAGE, "[知识库上传] 当前主 EmbeddingStore 实现: " + embeddingStore.getClass().getName(), true);
        juLog.write(Tip.MESSAGE, "[知识库上传] 当前企业 Redis EmbeddingStore 实现: " + enterpriseVectorStoreService.redisStore(enterpriseId).getClass().getName(), true);
        juLog.write(Tip.MESSAGE, "[知识库上传] 当前企业 Memory EmbeddingStore 实现: " + enterpriseVectorStoreService.memoryStore(enterpriseId).getClass().getName(), true);
        byte[] bytes = file.getBytes();
        juLog.write(Tip.MESSAGE, "[知识库上传] 文件字节大小: " + bytes.length, true);
        Document document = parse(file, bytes);

        List<TextSegment> segments = splitter.split(document).stream()
                .map(segment -> TextSegment.from(
                        segment.text(),
                        dev.langchain4j.data.document.Metadata.from(Map.of(
                                METADATA_ENTERPRISE_ID, String.valueOf(enterpriseId),
                                METADATA_FILE_NAME, String.valueOf(file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename())
                        ))
                ))
                .toList();
        juLog.write(Tip.MESSAGE, "[知识库切片] 企业 " + enterpriseId + " 文件 " + file.getOriginalFilename() + " 共切出片段数: " + segments.size(), true);

        EmbeddingStore<TextSegment> redisStore = enterpriseVectorStoreService.redisStore(enterpriseId);
        EmbeddingStore<TextSegment> memoryStore = enterpriseVectorStoreService.memoryStore(enterpriseId);
        List<String> redisDocumentIds = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            String segmentText = segment.text();
            String preview = segmentText == null ? "" : segmentText.substring(0, Math.min(segmentText.length(), 120)).replace("\n", " ");
            juLog.write(Tip.MESSAGE, "[知识库切片] 第 " + (i + 1) + " 片长度: " + (segmentText == null ? 0 : segmentText.length()) + "，预览: " + preview, true);
            Embedding embedding = embeddingModel.embed(segment).content();
            juLog.write(Tip.MESSAGE, "[向量写入] 企业 " + enterpriseId + " 第 " + (i + 1) + " 片开始双写向量存储", true);
            String redisDocumentId = redisStore.add(embedding, segment);
            redisDocumentIds.add(redisDocumentId);
            juLog.write(Tip.MESSAGE, "[向量写入] 企业 " + enterpriseId + " 第 " + (i + 1) + " 片 Redis 返回文档ID: " + redisDocumentId, true);
            memoryStore.add(embedding, segment);
            juLog.write(Tip.MESSAGE, "[向量写入] 企业 " + enterpriseId + " 第 " + (i + 1) + " 片双写完成", true);
            try {
                List<EmbeddingMatch<TextSegment>> redisMatches = redisStore.findRelevant(embedding, 1);
                juLog.write(Tip.MESSAGE, "[向量自检] 企业 " + enterpriseId + " 第 " + (i + 1) + " 片 Redis 写后即查命中数: " + redisMatches.size(), true);
                if (!redisMatches.isEmpty() && redisMatches.get(0).embedded() != null) {
                    juLog.write(Tip.MESSAGE,
                            "[向量自检] 企业 " + enterpriseId + " 第 " + (i + 1) + " 片 Redis 回查相似度: " + redisMatches.get(0).score()
                                    + "，预览: " + preview(redisMatches.get(0).embedded().text(), 120),
                            true);
                }
            } catch (Exception e) {
                juLog.write(Tip.WARRING,
                        "[向量自检] 企业 " + enterpriseId + " 第 " + (i + 1) + " 片 Redis 写后即查失败，不影响上传: " + e.getMessage(),
                        true);
            }
            List<EmbeddingMatch<TextSegment>> memoryMatches = memoryStore.findRelevant(embedding, 1);
            juLog.write(Tip.MESSAGE, "[向量自检] 企业 " + enterpriseId + " 第 " + (i + 1) + " 片 Memory 写后即查命中数: " + memoryMatches.size(), true);
            if (!memoryMatches.isEmpty() && memoryMatches.get(0).embedded() != null) {
                juLog.write(Tip.MESSAGE,
                        "[向量自检] 企业 " + enterpriseId + " 第 " + (i + 1) + " 片 Memory 回查相似度: " + memoryMatches.get(0).score()
                                + "，预览: " + preview(memoryMatches.get(0).embedded().text(), 120),
                        true);
            }
        }
        Map<String, Object> saved = knowledgeBaseStoreService.saveUploadedFile(
                enterpriseId,
                file.getOriginalFilename(),
                bytes,
                segments.size(),
                redisDocumentIds
        );
        juLog.write(Tip.MESSAGE, "[知识库上传] 企业 " + enterpriseId + " 文件已落盘，路径: " + saved.get("storedPath"), true);
        saved.put("segmentsIndexed", segments.size());
        juLog.write(Tip.MESSAGE, "[知识库上传] 企业 " + enterpriseId + " 文件处理完成: " + file.getOriginalFilename() + "，总片段数: " + segments.size(), true);
        return saved;
    }

    public Map<String, Object> deleteFileAndVectors(int enterpriseId, Map<String, Object> fileMetadata) throws IOException {
        if (fileMetadata == null) {
            return Map.of("deletedSegments", 0, "deletedRedisVectors", 0);
        }

        String storedFilename = String.valueOf(fileMetadata.getOrDefault("storedFilename", ""));
        String originalFilename = String.valueOf(fileMetadata.getOrDefault("originalFilename", ""));
        List<String> redisDocumentIds = readRedisDocumentIds(fileMetadata);
        int deletedRedisVectors = 0;
        try {
            if (!redisDocumentIds.isEmpty()) {
                deletedRedisVectors = enterpriseVectorStoreService.deleteRedisDocuments(enterpriseId, redisDocumentIds);
            }
            if (deletedRedisVectors <= 0 && !originalFilename.isBlank()) {
                deletedRedisVectors = enterpriseVectorStoreService.deleteRedisDocumentsByOriginalFilename(enterpriseId, originalFilename);
            }
            juLog.write(Tip.MESSAGE,
                    "[知识库删除] 企业 " + enterpriseId + " 文件 " + storedFilename + " 删除 Redis 向量数: " + deletedRedisVectors,
                    true);
        } catch (Exception e) {
            juLog.write(Tip.WARRING,
                    "[知识库删除] 企业 " + enterpriseId + " 文件 " + storedFilename + " 删除 Redis 向量失败: " + e.getMessage(),
                    true);
        }

        rebuildMemoryStore(enterpriseId, storedFilename);
        boolean deletedFile = knowledgeBaseStoreService.deleteFile(enterpriseId, storedFilename);
        int deletedSegments = Integer.parseInt(String.valueOf(fileMetadata.getOrDefault("segmentsIndexed", 0)));
        juLog.write(Tip.MESSAGE,
                "[知识库删除] 企业 " + enterpriseId + " 文件 " + storedFilename + " 删除结果: file=" + deletedFile + ", segments=" + deletedSegments,
                true);
        return Map.of(
                "deletedSegments", deletedSegments,
                "deletedRedisVectors", deletedRedisVectors,
                "deletedFile", deletedFile
        );
    }

    public int rebuildMemoryStore(int enterpriseId) throws IOException {
        return rebuildMemoryStore(enterpriseId, null);
    }

    private Document parse(MultipartFile file, byte[] bytes) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null) {
            filename = "unknown";
        }
        try (InputStream is = new java.io.ByteArrayInputStream(bytes)) {
            juLog.write(Tip.MESSAGE, "[知识库解析] 使用 Tika 解析文件: " + filename, true);
            return parser.parse(is);
        } catch (Exception e) {
            juLog.write(Tip.WARRING, "[知识库解析] Tika 解析失败，改为纯文本兜底: " + filename + "，原因: " + e.getMessage(), true);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return Document.from(text);
        }
    }

    private int rebuildMemoryStore(int enterpriseId, String excludedStoredFilename) throws IOException {
        List<Map<String, Object>> files = knowledgeBaseStoreService.listFiles(enterpriseId);
        List<TextSegment> segments = new ArrayList<>();
        List<Embedding> embeddings = new ArrayList<>();

        for (Map<String, Object> file : files) {
            String storedFilename = String.valueOf(file.getOrDefault("storedFilename", ""));
            if (Objects.equals(storedFilename, excludedStoredFilename)) {
                continue;
            }
            String storedPath = String.valueOf(file.getOrDefault("storedPath", ""));
            if (storedPath.isBlank() || Files.notExists(Path.of(storedPath))) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(Path.of(storedPath));
            String originalFilename = String.valueOf(file.getOrDefault("originalFilename", storedFilename));
            Document document = parse(originalFilename, bytes);
            List<TextSegment> fileSegments = splitter.split(document).stream()
                    .map(segment -> TextSegment.from(
                            segment.text(),
                            dev.langchain4j.data.document.Metadata.from(Map.of(
                                    METADATA_ENTERPRISE_ID, String.valueOf(enterpriseId),
                                    METADATA_FILE_NAME, originalFilename
                            ))
                    ))
                    .toList();
            for (TextSegment segment : fileSegments) {
                segments.add(segment);
                embeddings.add(embeddingModel.embed(segment).content());
            }
        }

        enterpriseVectorStoreService.rebuildMemoryStore(enterpriseId, segments, embeddings);
        juLog.write(Tip.MESSAGE,
                "[知识库删除] 企业 " + enterpriseId + " Memory 向量库已重建，保留片段数: " + segments.size(),
                true);
        return segments.size();
    }

    private Document parse(String filename, byte[] bytes) throws IOException {
        try (InputStream is = new java.io.ByteArrayInputStream(bytes)) {
            juLog.write(Tip.MESSAGE, "[知识库解析] 使用 Tika 解析文件: " + filename, true);
            return parser.parse(is);
        } catch (Exception e) {
            juLog.write(Tip.WARRING, "[知识库解析] Tika 解析失败，改为纯文本兜底: " + filename + "，原因: " + e.getMessage(), true);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return Document.from(text);
        }
    }

    private List<String> readRedisDocumentIds(Map<String, Object> fileMetadata) {
        Object value = fileMetadata.get("redisDocumentIds");
        if (value instanceof List<?> ids) {
            return ids.stream()
                    .map(String::valueOf)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
        }
        return List.of();
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
