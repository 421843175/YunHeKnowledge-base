package com.company.aiservice.netty;

import com.company.aiservice.protocol.ChatWsMessage;
import com.company.aiservice.protocol.WsMessage;
import com.company.aiservice.service.RagService;
import com.company.aiservice.utils.NettyUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class ChatWebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    // 记录 WebSocket 问答过程中的日志。
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // 保存 RAG 问答服务实例。
    private final RagService ragService;

    // 通过构造器注入 RAG 服务。
    public ChatWebSocketHandler(RagService ragService) {
        // 保存 RAG 服务引用。
        this.ragService = ragService;
    }

    /*
    当 WebSocket 握手真正完成后，它会主动给前端发一条：
type = system
告诉前端：
连接成功了
现在可以发问题了
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 当 WebSocket 握手真正完成后，再向前端发送 system 消息。
        if (evt == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            // 通知前端当前连接已经可以正常收发消息。
            writeJson(ctx, WsMessage.system("WebSocket 连接成功，已准备接收问题。"));
            // 当前事件已处理完成，直接返回。
            return;
        }
        // 其他事件继续交给父类处理。
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        // channelRead0 是每次前端提问进入 Netty 业务层的起点：解析 JSON -> 校验 type/data -> 调用 RagService.streamAnswer。
        // 定义前端传来的请求消息对象。
        ChatWsMessage request;
        try {
            // 先把前端发来的 JSON 文本反序列化成消息对象。
            request = NettyUtil.fromJson(frame.text());
        } catch (Exception e) {
            // 如果 JSON 不合法，就直接回一个错误消息。
            writeJson(ctx, WsMessage.error("消息格式不正确，请按 JSON 协议发送。"));
            // 当前请求处理结束。
            return;
        }

        // 当前后端只接受 user 类型的请求消息。
        if (!"user".equals(request.getType())) {
            // 如果类型不是 user，就提示前端消息类型不支持。
            writeJson(ctx, WsMessage.error("不支持的消息类型，仅支持 user。"));
            // 当前请求处理结束。
            return;
        }

        // 读取用户真正的问题文本，并去掉首尾空白字符。
        String question = request.getData() == null ? "" : request.getData().trim();
        // 如果问题为空，则直接返回错误提示。
        if (question.isBlank()) {
            // 把空问题错误反馈给前端。
            writeJson(ctx, WsMessage.error("问题不能为空"));
            // 当前请求处理结束。
            return;
        }

        // 调用 RAG 服务发起真流式问答。
        ragService.streamAnswer(
                question,
                // TODO:NOTICE 每接收到一个 token，就立刻通过 WebSocket 推给前端。
                token -> writeJson(ctx, WsMessage.chunk(token)),
                // 当模型完整输出结束时，给前端发送 done 消息。
                () -> writeJson(ctx, WsMessage.done()),
                // 当流式过程出错时，记录警告并回传 error 消息。
                error -> {
                    // 打印流式问答失败日志，方便排查问题。
                    log.warn("WebSocket 问答失败: {}", error);
                    // 将错误信息包装成统一协议返回给前端。
                    writeJson(ctx, WsMessage.error(error == null || error.isBlank() ? "问答失败" : error));
                }
        );
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 打印当前 WebSocket 通道异常日志。
        log.error("WebSocket 通道异常", cause);
        // 如果连接还活着，就尽量给前端发一个错误消息再关闭连接。
        if (ctx.channel().isActive()) {
            try {
                // 把异常信息转成统一 JSON 协议返回给前端。
                ctx.writeAndFlush(new TextWebSocketFrame(NettyUtil.toJson(
                        WsMessage.error(cause.getMessage() == null ? "服务异常" : cause.getMessage())
                ))).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception ignored) {
                // 如果连错误消息都发送失败，就直接关闭连接。
                ctx.close();
            }
        } else {
            // 如果连接本来就已经失效，则直接关闭上下文。
            ctx.close();
        }
    }

    // 统一把消息对象按 JSON 文本写回给前端。
    private void writeJson(ChannelHandlerContext ctx, ChatWsMessage message) {
        // 如果连接已经不可用，就不再继续写数据。
        if (!ctx.channel().isActive()) {
            // 当前写操作直接结束。
            return;
        }
        // 把消息对象序列化成 JSON 文本并写成 WebSocket 文本帧。
        ctx.writeAndFlush(new TextWebSocketFrame(NettyUtil.toJson(message)))
                // 如果底层写出失败，则把异常继续抛给 Netty 流程处理。
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
