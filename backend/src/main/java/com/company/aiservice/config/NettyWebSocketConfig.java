package com.company.aiservice.config;

import com.company.aiservice.netty.ChatWebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NettyWebSocketConfig {

    private static final Logger log = LoggerFactory.getLogger(NettyWebSocketConfig.class);

    @Value("${netty.websocket.port:8083}")
    private int port;

    @Value("${netty.websocket.path:/ws/chat}")
    private String websocketPath;

    private final ChatWebSocketHandler chatWebSocketHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyWebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @PostConstruct
    public void start() {
        Thread serverThread = new Thread(this::runServer, "netty-websocket-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void runServer() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(65536));
                            pipeline.addLast(new HttpServerExpectContinueHandler());
                            pipeline.addLast("ws-handshake-log", new io.netty.channel.SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, FullHttpRequest msg) {
                                    String upgrade = msg.headers().get(HttpHeaderNames.UPGRADE, "");
                                    log.info("Netty 收到 HTTP 请求: uri={}, upgrade={}, connection={}",
                                            msg.uri(),
                                            upgrade,
                                            msg.headers().get(HttpHeaderNames.CONNECTION, ""));
                                    ctx.fireChannelRead(msg.retain());
                                }
                            });
                            pipeline.addLast(new ChunkedWriteHandler());
                            pipeline.addLast(new WebSocketServerProtocolHandler(websocketPath, null, true, 65536, false, true));
                            pipeline.addLast(chatWebSocketHandler);
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            log.info("Netty WebSocket started at ws://127.0.0.1:{}{}", port, websocketPath);
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Netty WebSocket server interrupted", e);
        } catch (Exception e) {
            log.error("Netty WebSocket server startup failed", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
