package com.company.aiservice.service;

import com.company.aiservice.JULOG.JULog;
import com.company.aiservice.JULOG.Tip;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeBaseWarmupService {

    private final KnowledgeBaseStoreService knowledgeBaseStoreService;
    private final DocumentService documentService;
    private final StateService stateService;
    private final JULog juLog;

    public KnowledgeBaseWarmupService(KnowledgeBaseStoreService knowledgeBaseStoreService,
                                      DocumentService documentService,
                                      StateService stateService,
                                      JULog juLog) {
        this.knowledgeBaseStoreService = knowledgeBaseStoreService;
        this.documentService = documentService;
        this.stateService = stateService;
        this.juLog = juLog;
    }

    @PostConstruct
    public void warmup() {
        try {
            List<Integer> enterpriseIds = knowledgeBaseStoreService.listEnterpriseIds();
            juLog.write(Tip.MESSAGE,
                    "[知识库预热] 启动预热开始，待恢复企业数: " + enterpriseIds.size(),
                    true);
            for (Integer enterpriseId : enterpriseIds) {
                if (enterpriseId == null || enterpriseId <= 0) {
                    continue;
                }
                int rebuiltSegments = documentService.rebuildMemoryStore(enterpriseId);
                stateService.resetSegments(enterpriseId, rebuiltSegments);
                juLog.write(Tip.MESSAGE,
                        "[知识库预热] 企业 " + enterpriseId + " 预热完成，恢复片段数: " + rebuiltSegments,
                        true);
            }
            juLog.write(Tip.MESSAGE, "[知识库预热] 启动预热结束", true);
        } catch (IOException e) {
            juLog.write(Tip.ERROR,
                    "[知识库预热] 启动预热失败: " + e.getMessage(),
                    true);
        }
    }
}

