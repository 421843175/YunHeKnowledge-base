package com.company.aiservice.controller;

import com.company.aiservice.security.JwtUserClaims;
import com.company.aiservice.security.SecurityUtils;
import com.company.aiservice.service.DocumentService;
import com.company.aiservice.service.KnowledgeBaseStoreService;
import com.company.aiservice.service.StateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
@Validated
public class DocController {

    private final DocumentService documentService;
    private final StateService stateService;
    private final KnowledgeBaseStoreService knowledgeBaseStoreService;

    public DocController(DocumentService documentService,
                         StateService stateService,
                         KnowledgeBaseStoreService knowledgeBaseStoreService) {
        this.documentService = documentService;
        this.stateService = stateService;
        this.knowledgeBaseStoreService = knowledgeBaseStoreService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(HttpServletRequest request,
                                                      @RequestParam("files") MultipartFile[] files) throws IOException {
        JwtUserClaims claims = SecurityUtils.getRequiredClaims(request);
        int enterpriseId = claims.enterpriseId();
        int totalSegments = 0;
        List<Map<String, Object>> uploadedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            Map<String, Object> fileResult = documentService.indexFile(enterpriseId, file);
            totalSegments += Integer.parseInt(String.valueOf(fileResult.getOrDefault("segmentsIndexed", 0)));
            uploadedFiles.add(fileResult);
        }
        stateService.addSegments(enterpriseId, totalSegments);
        Map<String, Object> knowledgeBaseStats = knowledgeBaseStoreService.stats(enterpriseId);
        long redisTotalSegments = stateService.totalSegments(enterpriseId);
        long mergedTotalSegments = redisTotalSegments > 0
                ? redisTotalSegments
                : Long.parseLong(String.valueOf(knowledgeBaseStats.getOrDefault("totalSegments", 0)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterpriseId", enterpriseId);
        result.put("uploadedFiles", uploadedFiles);
        result.put("segmentsIndexed", totalSegments);
        result.put("uploadedFilesCount", knowledgeBaseStats.getOrDefault("totalFiles", 0));
        result.put("segmentsIndexedTotal", mergedTotalSegments);
        result.put("redisSegmentsTotal", redisTotalSegments);
        result.put("knowledgeBase", knowledgeBaseStats);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> files(HttpServletRequest request) throws IOException {
        JwtUserClaims claims = SecurityUtils.getRequiredClaims(request);
        int enterpriseId = claims.enterpriseId();
        return ResponseEntity.ok(knowledgeBaseStoreService.listFiles(enterpriseId));
    }

    @GetMapping("/download")
    public ResponseEntity<UrlResource> download(HttpServletRequest request,
                                                @RequestParam String storedFilename) throws IOException {
        JwtUserClaims claims = SecurityUtils.getRequiredClaims(request);
        int enterpriseId = claims.enterpriseId();
        Map<String, Object> file = knowledgeBaseStoreService.findFile(enterpriseId, storedFilename);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }

        Path storedPath = Path.of(String.valueOf(file.get("storedPath")));
        UrlResource resource = new UrlResource(storedPath.toUri());
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String originalFilename = String.valueOf(file.getOrDefault("originalFilename", storedFilename));
        String encodedFilename = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @DeleteMapping("/file")
    public ResponseEntity<Map<String, Object>> delete(HttpServletRequest request,
                                                      @RequestParam String storedFilename) throws IOException {
        JwtUserClaims claims = SecurityUtils.getRequiredClaims(request);
        int enterpriseId = claims.enterpriseId();
        Map<String, Object> file = knowledgeBaseStoreService.findFile(enterpriseId, storedFilename);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> deleteResult = documentService.deleteFileAndVectors(enterpriseId, file);
        stateService.subtractSegments(enterpriseId,
                Integer.parseInt(String.valueOf(deleteResult.getOrDefault("deletedSegments", 0))));
        Map<String, Object> stats = knowledgeBaseStoreService.stats(enterpriseId);
        long redisTotalSegments = stateService.totalSegments(enterpriseId);
        long mergedTotalSegments = redisTotalSegments > 0
                ? redisTotalSegments
                : Long.parseLong(String.valueOf(stats.getOrDefault("totalSegments", 0)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", Boolean.TRUE.equals(deleteResult.get("deletedFile")));
        result.put("deletedRedisVectors", deleteResult.getOrDefault("deletedRedisVectors", 0));
        result.put("uploadedFilesCount", stats.getOrDefault("totalFiles", 0));
        result.put("segmentsIndexedTotal", mergedTotalSegments);
        result.put("files", stats.getOrDefault("files", List.of()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(HttpServletRequest request) throws IOException {
        JwtUserClaims claims = SecurityUtils.getRequiredClaims(request);
        int enterpriseId = claims.enterpriseId();
        Map<String, Object> knowledgeBaseStats = knowledgeBaseStoreService.stats(enterpriseId);
        long redisTotalSegments = stateService.totalSegments(enterpriseId);
        long mergedTotalSegments = redisTotalSegments > 0
                ? redisTotalSegments
                : Long.parseLong(String.valueOf(knowledgeBaseStats.getOrDefault("totalSegments", 0)));
        Map<String, Object> result = new LinkedHashMap<>(knowledgeBaseStats);
        result.put("enterpriseId", enterpriseId);
        result.put("uploadedFilesCount", knowledgeBaseStats.getOrDefault("totalFiles", 0));
        result.put("segmentsIndexedTotal", mergedTotalSegments);
        result.put("localSegmentsTotal", knowledgeBaseStats.getOrDefault("totalSegments", 0));
        result.put("redisSegmentsTotal", redisTotalSegments);
        result.put("segmentsIndexed", knowledgeBaseStats.getOrDefault("totalSegments", 0));
        result.put("uploadedFiles", knowledgeBaseStats.getOrDefault("files", List.of()));
        return ResponseEntity.ok(result);
    }
}
