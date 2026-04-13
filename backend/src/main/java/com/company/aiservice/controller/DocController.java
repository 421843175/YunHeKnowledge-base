package com.company.aiservice.controller;

import com.company.aiservice.service.DocumentService;
import com.company.aiservice.service.KnowledgeBaseStoreService;
import com.company.aiservice.service.StateService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
@Validated
public class DocController {

    // 保存文档上传与向量化服务实例。
    private final DocumentService documentService;
    // 保存原有状态统计服务实例。
    private final StateService stateService;
    // 保存知识库本地元信息服务实例。
    private final KnowledgeBaseStoreService knowledgeBaseStoreService;

    // 通过构造器注入所需服务。
    public DocController(DocumentService documentService,
                         StateService stateService,
                         KnowledgeBaseStoreService knowledgeBaseStoreService) {
        // 保存文档服务引用。
        this.documentService = documentService;
        // 保存状态服务引用。
        this.stateService = stateService;
        // 保存知识库本地存储服务引用。
        this.knowledgeBaseStoreService = knowledgeBaseStoreService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("files") MultipartFile[] files) throws IOException {
        // upload 接口是文件入库总入口：接收多文件后，逐个交给 DocumentService 做解析、切片、向量化、双写入库与本地落盘。
        // 记录本次上传累计写入的总片段数。
        int totalSegments = 0;
        // 保存本次每个文件的详细处理结果。
        List<Map<String, Object>> uploadedFiles = new ArrayList<>();
        // 逐个处理前端上传的文件。
        for (MultipartFile file : files) {
            // 调用文档服务完成单文件切分、向量化、入库和落盘。
            Map<String, Object> fileResult = documentService.indexFile(file);
            // 累加本次上传的片段数量。
            totalSegments += Integer.parseInt(String.valueOf(fileResult.getOrDefault("segmentsIndexed", 0)));
            // 把单文件处理结果加入返回列表。
            uploadedFiles.add(fileResult);
        }
        // 将本次新增片段数量继续同步到原有 Redis 统计中。
        stateService.addSegments(totalSegments);
        // 读取当前知识库累计统计信息。
        Map<String, Object> knowledgeBaseStats = knowledgeBaseStoreService.stats();
        // 优先使用 Redis 中的累计片段总数。
        long redisTotalSegments = stateService.totalSegments();
        // 如果 Redis 中没有值，则回退到本地元信息累计值。
        long mergedTotalSegments = redisTotalSegments > 0
                ? redisTotalSegments
                : Long.parseLong(String.valueOf(knowledgeBaseStats.getOrDefault("totalSegments", 0)));
        // 构造统一返回结果对象。
        Map<String, Object> result = new LinkedHashMap<>();
        // 返回本次上传的文件详情列表。
        result.put("uploadedFiles", uploadedFiles);
        // 返回本次上传累计切片数。
        result.put("segmentsIndexed", totalSegments);
        // 返回累计已上传文件总数，兼容前端直接读取历史文件数。
        result.put("uploadedFilesCount", knowledgeBaseStats.getOrDefault("totalFiles", 0));
        // 返回累计向量片段总数，兼容前端直接读取历史片段数。
        result.put("segmentsIndexedTotal", mergedTotalSegments);
        // 返回 Redis 中维护的累计切片数，方便排查 Redis 是否生效。
        result.put("redisSegmentsTotal", redisTotalSegments);
        // 返回本地知识库统计信息，方便确认文件到底落到哪了。
        result.put("knowledgeBase", knowledgeBaseStats);
        // 把处理结果返回给前端。
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() throws IOException {
        // 直接读取本地知识库统计信息。
        Map<String, Object> knowledgeBaseStats = knowledgeBaseStoreService.stats();
        // 读取 Redis 中的累计片段数。
        long redisTotalSegments = stateService.totalSegments();
        // 如果 Redis 中没有累计值，则回退到本地元信息里的累计片段数。
        long mergedTotalSegments = redisTotalSegments > 0
                ? redisTotalSegments
                : Long.parseLong(String.valueOf(knowledgeBaseStats.getOrDefault("totalSegments", 0)));
        // 构造兼容前端旧字段和新字段的统一返回对象。
        Map<String, Object> result = new LinkedHashMap<>(knowledgeBaseStats);
        // 返回累计已上传文件总数，供前端“已上传文件数”直接展示。
        result.put("uploadedFilesCount", knowledgeBaseStats.getOrDefault("totalFiles", 0));
        // 返回累计向量片段总数，供前端“累计向量片段”直接展示。
        result.put("segmentsIndexedTotal", mergedTotalSegments);
        // 返回本地元信息统计出的累计向量片段数，便于和 Redis 对比。
        result.put("localSegmentsTotal", knowledgeBaseStats.getOrDefault("totalSegments", 0));
        // 把 Redis 中的片段总数也一并返回，方便对比是否一致。
        result.put("redisSegmentsTotal", redisTotalSegments);
        // 兼容有些前端把“本次切分片段”错误绑定到 segmentsIndexed 字段的情况。
        result.put("segmentsIndexed", knowledgeBaseStats.getOrDefault("totalSegments", 0));
        // 兼容有些前端把“已上传文件数”错误绑定到 uploadedFiles 数组长度的情况。
        result.put("uploadedFiles", knowledgeBaseStats.getOrDefault("files", List.of()));
        // 将统计结果返回给前端。
        return ResponseEntity.ok(result);
    }
}
