package com.company.aiservice.controller;

import com.company.aiservice.security.JwtUserClaims;
import com.company.aiservice.security.SecurityUtils;
import com.company.aiservice.service.ChatHistoryService;
import com.company.aiservice.service.RagService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagService ragService;
    private final ChatHistoryService chatHistoryService;

    public ChatController(RagService ragService,
                          ChatHistoryService chatHistoryService) {
        this.ragService = ragService;
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestParam("enterpriseId") int enterpriseId,
                                                    @RequestParam("q") String q) {
        String answer = ragService.answer(enterpriseId, q);
        return ResponseEntity.ok(Map.of("answer", answer));
    }

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> history(HttpServletRequest request,
                                                             @RequestParam(value = "keyword", required = false) String keyword) {
        JwtUserClaims claims = SecurityUtils.getRequiredClaims(request);
        return ResponseEntity.ok(chatHistoryService.list(claims.enterpriseId(), claims.userId(), keyword));
    }

    @PostMapping("/history")
    public ResponseEntity<Map<String, Object>> appendHistory(HttpServletRequest request,
                                                             @RequestBody Map<String, String> payload) {
        JwtUserClaims claims = SecurityUtils.getRequiredClaims(request);
        String question = String.valueOf(payload.getOrDefault("question", "")).trim();
        String answer = String.valueOf(payload.getOrDefault("answer", "")).trim();
        chatHistoryService.append(claims.enterpriseId(), claims.userId(), question, answer);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("items", chatHistoryService.list(claims.enterpriseId(), claims.userId(), ""));
        return ResponseEntity.ok(result);
    }
}
