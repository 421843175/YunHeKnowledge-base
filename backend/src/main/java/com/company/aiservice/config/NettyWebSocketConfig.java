package com.company.aiservice.config;

import com.company.aiservice.netty.ChatWebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
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
        // Netty 独立端口启动入口：初始化 boss/worker 线程组，配置 WebSocket pipeline，并绑定 /ws/chat。
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
                            // TODO:NOTICE pipeline处理器链
                            //  顺序就是 Netty 消息处理顺序：HTTP 编解码 -> 聚合 -> 大块写出
                            //  -> WebSocket 协议升级 -> 业务聊天处理。
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(65536));
                            ch.pipeline().addLast(new ChunkedWriteHandler());
                            ch.pipeline().addLast(new WebSocketServerProtocolHandler(websocketPath, null, true, 65536));
                            ch.pipeline().addLast(chatWebSocketHandler);
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
