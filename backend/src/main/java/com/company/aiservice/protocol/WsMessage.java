package com.company.aiservice.protocol;

//规定传过去的MSG格式
public final class WsMessage {

    private WsMessage() {
    }

    public static ChatWsMessage system(String data) {
        return new ChatWsMessage("system", data);
    }

    public static ChatWsMessage chunk(String data) {
        return new ChatWsMessage("chunk", data);
    }

    public static ChatWsMessage done() {
        return new ChatWsMessage("done", "");
    }

    public static ChatWsMessage error(String data) {
        return new ChatWsMessage("error", data);
    }

    public static ChatWsMessage user(String data) {
        return new ChatWsMessage("user", data);
    }
}

