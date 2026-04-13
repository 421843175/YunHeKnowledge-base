package com.company.aiservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeBaseStoreService {

    // 保存上传文件的根目录配置。
    private final String uploadDir;
    // 保存知识库元信息文件路径配置。
    private final String metadataFile;
    // 保存 JSON 处理器实例。
    private final ObjectMapper objectMapper;
    // 保存上传目录路径对象。
    private Path uploadPath;
    // 保存元信息文件路径对象。
    private Path metadataPath;

    // 通过构造器注入本地存储配置。
    public KnowledgeBaseStoreService(@Value("${app.knowledge.upload-dir:backend/data/uploads}") String uploadDir,
                                     @Value("${app.knowledge.metadata-file:backend/data/knowledge-metadata.json}") String metadataFile) {
        // 保存上传目录配置值。
        this.uploadDir = uploadDir;
        // 保存元信息文件配置值。
        this.metadataFile = metadataFile;
        // 创建 JSON 处理器实例。
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() throws IOException {
        // 根据配置生成上传目录路径。
        this.uploadPath = Path.of(uploadDir).toAbsolutePath().normalize();
        // 根据配置生成元信息文件路径。
        this.metadataPath = Path.of(metadataFile).toAbsolutePath().normalize();
        // 确保上传目录真实存在。
        Files.createDirectories(uploadPath);
        // 确保元信息文件的父目录存在。
        if (metadataPath.getParent() != null) {
            // 创建元信息文件父目录，避免后续写文件失败。
            Files.createDirectories(metadataPath.getParent());
        }
        // 如果元信息文件还不存在，就先创建一个空数组文件。
        if (Files.notExists(metadataPath)) {
            // 写入空数组，表示当前还没有任何知识库记录。
            objectMapper.writeValue(metadataPath.toFile(), new ArrayList<>());
        }
    }

    // 保存上传的原始文件，并记录索引后的元信息。
    public synchronized Map<String, Object> saveUploadedFile(String originalFilename, byte[] content, int segmentsIndexed) throws IOException {
        // 生成唯一文件名前缀，避免同名文件互相覆盖。
        String uniqueName = UUID.randomUUID() + "_" + sanitizeFilename(originalFilename);
        // 计算目标文件完整路径。
        Path targetFile = uploadPath.resolve(uniqueName);
        // 把上传文件真实写入本地磁盘。
        Files.copy(new java.io.ByteArrayInputStream(content), targetFile, StandardCopyOption.REPLACE_EXISTING);
        // 读取现有全部元信息记录。
        List<Map<String, Object>> metadata = readMetadata();
        // 构造当前文件的元信息对象。
        Map<String, Object> item = new LinkedHashMap<>();
        // 记录原始文件名，方便前端展示。
        item.put("originalFilename", originalFilename);
        // 记录本地实际保存后的文件名，方便排查。
        item.put("storedFilename", uniqueName);
        // 记录本地绝对路径，方便你确认文件落盘位置。
        item.put("storedPath", targetFile.toString());
        // 记录当前文件切分并入库的片段数量。
        item.put("segmentsIndexed", segmentsIndexed);
        // 记录上传时间，方便定位历史上传记录。
        item.put("uploadedAt", LocalDateTime.now().toString());
        // 把当前文件记录追加到元信息列表中。
        metadata.add(item);
        // 将更新后的元信息重新写回本地 JSON 文件。
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata);
        // 返回当前文件的元信息给调用方。
        return item;
    }

    // 返回当前知识库的统计结果。
    public synchronized Map<String, Object> stats() throws IOException {
        // 读取现有全部元信息记录。
        List<Map<String, Object>> metadata = readMetadata();
        // 统计当前已上传文件总数。
        int totalFiles = metadata.size();
        // 累加当前所有文件的总片段数。
        long totalSegments = metadata.stream()
                .mapToLong(item -> Long.parseLong(String.valueOf(item.getOrDefault("segmentsIndexed", 0))))
                .sum();
        // 构造统一统计结果对象。
        Map<String, Object> result = new LinkedHashMap<>();
        // 返回当前本地上传目录。
        result.put("uploadDir", uploadPath.toString());
        // 返回当前本地元信息文件路径。
        result.put("metadataFile", metadataPath.toString());
        // 返回已上传文件总数。
        result.put("totalFiles", totalFiles);
        // 返回累计切片总数。
        result.put("totalSegments", totalSegments);
        // 返回完整文件清单，方便前端查看每个文件去向。
        result.put("files", metadata);
        // 返回统计结果。
        return result;
    }

    // 从本地 JSON 文件读取全部知识库元信息。
    private List<Map<String, Object>> readMetadata() throws IOException {
        // 如果元信息文件不存在，则直接返回空列表。
        if (Files.notExists(metadataPath)) {
            // 返回空列表表示当前还没有知识库记录。
            return new ArrayList<>();
        }
        // 读取并反序列化元信息文件内容。
        List<Map<String, Object>> data = objectMapper.readValue(metadataPath.toFile(), new TypeReference<List<Map<String, Object>>>() {});
        // 如果文件内容为空，则返回空列表兜底。
        return data == null ? new ArrayList<>() : new ArrayList<>(data);
    }

    // 对原始文件名做简单清洗，避免路径字符引发问题。
    private String sanitizeFilename(String filename) {
        // 如果文件名为空，就给一个默认名称。
        if (filename == null || filename.isBlank()) {
            // 返回默认文件名，避免出现空名称。
            return "unknown-file";
        }
        // 把非法路径字符替换成下划线。
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}

