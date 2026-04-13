package com.company.aiservice.controller;

import com.company.aiservice.service.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagService ragService;

    public ChatController(RagService ragService) {
        this.ragService = ragService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestParam("q") String q) {
        String answer = ragService.answer(q);
        return ResponseEntity.ok(Map.of("answer", answer));
    }
}
