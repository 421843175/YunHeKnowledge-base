package com.company.aiservice.netty;

import com.company.aiservice.protocol.ChatWsMessage;
import com.company.aiservice.protocol.WsMessage;
import com.company.aiservice.service.RagService;
import com.company.aiservice.utils.NettyUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class ChatWebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final AttributeKey<Integer> ENTERPRISE_ID_ATTR = AttributeKey.valueOf("enterpriseId");

    private final RagService ragService;

    public ChatWebSocketHandler(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        log.info("WebSocket userEventTriggered: {}", evt);
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete handshakeComplete) {
            String requestUri = handshakeComplete.requestUri();
            log.info("WebSocket 握手事件触发，请求 URI={}", requestUri);
            QueryStringDecoder query = new QueryStringDecoder(requestUri == null ? "/ws/chat" : requestUri);
            String enterpriseIdValue = query.parameters().getOrDefault("enterpriseId", java.util.List.of("0")).get(0);
            int enterpriseId;
            try {
                enterpriseId = Integer.parseInt(enterpriseIdValue);
            } catch (NumberFormatException ex) {
                log.warn("WebSocket 握手成功，但 enterpriseId 非法: {}", enterpriseIdValue);
                writeJson(ctx, WsMessage.error("enterpriseId 不合法"));
                ctx.close();
                return;
            }
            if (enterpriseId <= 0) {
                log.warn("WebSocket 握手成功，但 enterpriseId 缺失或无效: {}", enterpriseId);
                writeJson(ctx, WsMessage.error("缺少 enterpriseId，无法连接企业知识库。"));
                ctx.close();
                return;
            }
            ctx.channel().attr(ENTERPRISE_ID_ATTR).set(enterpriseId);
            log.info("WebSocket 握手完成，enterpriseId={}", enterpriseId);
            writeJson(ctx, WsMessage.system("WebSocket 连接成功，已准备接收问题。"));
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        ChatWsMessage request;
        try {
            request = NettyUtil.fromJson(frame.text());
        } catch (Exception e) {
            log.warn("WebSocket 收到非法 JSON: {}", frame.text());
            writeJson(ctx, WsMessage.error("消息格式不正确，请按 JSON 协议发送。"));
            return;
        }

        if (!"user".equals(request.getType())) {
            writeJson(ctx, WsMessage.error("不支持的消息类型，仅支持 user。"));
            return;
        }

        String question = request.getData() == null ? "" : request.getData().trim();
        if (question.isBlank()) {
            writeJson(ctx, WsMessage.error("问题不能为空"));
            return;
        }

        Integer enterpriseId = ctx.channel().attr(ENTERPRISE_ID_ATTR).get();
        if (enterpriseId == null || enterpriseId <= 0) {
            log.warn("WebSocket 收到问题，但 enterpriseId 未绑定");
            writeJson(ctx, WsMessage.error("缺少 enterpriseId，无法连接企业知识库。"));
            return;
        }

        log.info("WebSocket 开始处理问题，enterpriseId={}, question={}", enterpriseId, question);
        ragService.streamAnswer(
                enterpriseId,
                question,
                token -> writeJson(ctx, WsMessage.chunk(token)),
                () -> writeJson(ctx, WsMessage.done()),
                error -> {
                    log.warn("WebSocket 问答失败，enterpriseId={}, error={}", enterpriseId, error);
                    writeJson(ctx, WsMessage.error(error == null || error.isBlank() ? "问答失败" : error));
                }
        );
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("WebSocket TCP 连接建立: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("WebSocket 连接关闭: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket 通道异常", cause);
        if (ctx.channel().isActive()) {
            try {
                ctx.writeAndFlush(new TextWebSocketFrame(NettyUtil.toJson(
                        WsMessage.error(cause.getMessage() == null ? "服务异常" : cause.getMessage())
                ))).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception ignored) {
                ctx.close();
            }
        } else {
            ctx.close();
        }
    }

    private void writeJson(ChannelHandlerContext ctx, ChatWsMessage message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        ctx.writeAndFlush(new TextWebSocketFrame(NettyUtil.toJson(message)))
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
