package com.company.aiservice.utils;

import com.company.aiservice.protocol.ChatWsMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class NettyUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private NettyUtil() {
    }

    public static String toJson(ChatWsMessage message) {
        try {
            return OBJECT_MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WebSocket 消息序列化失败", e);
                        }
    }

    public static ChatWsMessage fromJson(String text) {
        try {
            return OBJECT_MAPPER.readValue(text, ChatWsMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("WebSocket 消息格式不正确", e);
        }
    }
}
