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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeBaseStoreService {

    private final String uploadDir;
    private final String metadataFile;
    private final ObjectMapper objectMapper;
    private Path uploadPath;
    private Path metadataPath;

    public KnowledgeBaseStoreService(@Value("${app.knowledge.upload-dir:backend/data/uploads}") String uploadDir,
                                     @Value("${app.knowledge.metadata-file:backend/data/knowledge-metadata.json}") String metadataFile) {
        this.uploadDir = uploadDir;
        this.metadataFile = metadataFile;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() throws IOException {
        this.uploadPath = Path.of(uploadDir).toAbsolutePath().normalize();
        this.metadataPath = Path.of(metadataFile).toAbsolutePath().normalize();
        Files.createDirectories(uploadPath);
        if (metadataPath.getParent() != null) {
            Files.createDirectories(metadataPath.getParent());
        }
        if (Files.notExists(metadataPath)) {
            objectMapper.writeValue(metadataPath.toFile(), new ArrayList<>());
        }
    }

    public synchronized Map<String, Object> saveUploadedFile(int enterpriseId,
                                                             String originalFilename,
                                                             byte[] content,
                                                             int segmentsIndexed,
                                                             List<String> redisDocumentIds) throws IOException {
        Path enterpriseUploadPath = uploadPath.resolve("enterprise_" + enterpriseId);
        Files.createDirectories(enterpriseUploadPath);

        String uniqueName = UUID.randomUUID() + "_" + sanitizeFilename(originalFilename);
        Path targetFile = enterpriseUploadPath.resolve(uniqueName);
        Files.copy(new java.io.ByteArrayInputStream(content), targetFile, StandardCopyOption.REPLACE_EXISTING);

        List<Map<String, Object>> metadata = readMetadata();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("enterpriseId", enterpriseId);
        item.put("originalFilename", originalFilename);
        item.put("storedFilename", uniqueName);
        item.put("storedPath", targetFile.toString());
        item.put("segmentsIndexed", segmentsIndexed);
        item.put("redisDocumentIds", redisDocumentIds == null ? List.of() : redisDocumentIds);
        item.put("uploadedAt", LocalDateTime.now().toString());
        metadata.add(item);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata);
        return item;
    }

    public synchronized Map<String, Object> stats(int enterpriseId) throws IOException {
        List<Map<String, Object>> allMetadata = readMetadata();
        List<Map<String, Object>> metadata = allMetadata.stream()
                .filter(item -> Integer.parseInt(String.valueOf(item.getOrDefault("enterpriseId", 0))) == enterpriseId)
                .map(this::normalizeFileMetadata)
                .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("uploadedAt", "")), Comparator.reverseOrder()))
                .toList();
        int totalFiles = metadata.size();
        long totalSegments = metadata.stream()
                .mapToLong(item -> Long.parseLong(String.valueOf(item.getOrDefault("segmentsIndexed", 0))))
                .sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterpriseId", enterpriseId);
        result.put("uploadDir", uploadPath.resolve("enterprise_" + enterpriseId).toString());
        result.put("metadataFile", metadataPath.toString());
        result.put("totalFiles", totalFiles);
        result.put("totalSegments", totalSegments);
        result.put("files", metadata);
        return result;
    }

    public synchronized List<Integer> listEnterpriseIds() throws IOException {
        return readMetadata().stream()
                .map(item -> Integer.parseInt(String.valueOf(item.getOrDefault("enterpriseId", 0))))
                .filter(id -> id > 0)
                .distinct()
                .sorted()
                .toList();
    }

    public synchronized List<Map<String, Object>> listFiles(int enterpriseId) throws IOException {
        return new ArrayList<>((List<Map<String, Object>>) stats(enterpriseId).getOrDefault("files", List.of()));
    }

    public synchronized Map<String, Object> findFile(int enterpriseId, String storedFilename) throws IOException {
        return readMetadata().stream()
                .filter(item -> Integer.parseInt(String.valueOf(item.getOrDefault("enterpriseId", 0))) == enterpriseId)
                .filter(item -> storedFilename.equals(String.valueOf(item.getOrDefault("storedFilename", ""))))
                .findFirst()
                .map(this::normalizeFileMetadata)
                .orElse(null);
    }

    public synchronized boolean deleteFile(int enterpriseId, String storedFilename) throws IOException {
        List<Map<String, Object>> metadata = readMetadata();
        boolean removed = false;
        List<Map<String, Object>> updated = new ArrayList<>();
        for (Map<String, Object> item : metadata) {
            boolean sameEnterprise = Integer.parseInt(String.valueOf(item.getOrDefault("enterpriseId", 0))) == enterpriseId;
            boolean sameFile = storedFilename.equals(String.valueOf(item.getOrDefault("storedFilename", "")));
            if (sameEnterprise && sameFile) {
                removed = true;
                String storedPath = String.valueOf(item.getOrDefault("storedPath", ""));
                if (!storedPath.isBlank()) {
                    Files.deleteIfExists(Path.of(storedPath));
                }
                continue;
            }
            updated.add(item);
        }
        if (removed) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), updated);
        }
        return removed;
    }

    private List<Map<String, Object>> readMetadata() throws IOException {
        if (Files.notExists(metadataPath)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> data = objectMapper.readValue(metadataPath.toFile(), new TypeReference<List<Map<String, Object>>>() {});
        return data == null ? new ArrayList<>() : new ArrayList<>(data);
    }

    private Map<String, Object> normalizeFileMetadata(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>(source);
        Object redisDocumentIds = normalized.get("redisDocumentIds");
        if (redisDocumentIds instanceof List<?> ids) {
            normalized.put("redisDocumentIds", ids.stream().map(String::valueOf).toList());
        } else {
            normalized.put("redisDocumentIds", List.of());
        }
        return normalized;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unknown-file";
        }
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
