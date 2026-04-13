# 项目辅助阅读

---

## 一、上传文件 -> 识别 -> 切片 -> 向量化 -> 落盘 流程

### 总入口
1. 前端上传文件到 `POST /api/docs/upload`
2. `DocController.upload(...)` 接收多文件
3. 循环调用 `DocumentService.indexFile(file)` 处理每个文件
4. `DocumentService` 完成：
   - 读字节
   - 文件解析
   - 文档切片
   - 逐片 embedding
   - 写入 Redis 向量库
   - 写入内存向量库
   - 原始文件落盘
   - 元信息写入 `knowledge-metadata.json`
5. `StateService` 额外把累计片段数同步到 Redis 统计键里

### 关键代码文件与阅读顺序

#### 1）上传接口入口
- 文件：`backend/src/main/java/com/company/aiservice/controller/DocController.java`
- 建议重点：
  - `upload(...)`
  - `stats(...)`

#### 2）单文件处理主流程
- 文件：`backend/src/main/java/com/company/aiservice/service/DocumentService.java`
- 建议重点：
  - `indexFile(...)`
  - `parse(...)`
  - `preview(...)`

#### 3）切片配置来源
- 文件：`backend/src/main/java/com/company/aiservice/config/OpenAiConfig.java`
- 建议重点：
  - `documentSplitter()`

#### 4）文件落盘与元信息记录
- 文件：`backend/src/main/java/com/company/aiservice/service/KnowledgeBaseStoreService.java`
- 建议重点阅读：
  - `init()`
  - `saveUploadedFile(...)`
  - `stats()`
  - `readMetadata()`

#### 5）累计片段数统计
- 文件：`backend/src/main/java/com/company/aiservice/service/StateService.java`
- 建议重点：
  - `addSegments(...)`
  - `totalSegments()`

---

## 二、用户问 AI 问题后的检索流程

- 收到问题
- 问题向量化
- 查 Redis top5
- 查 Memory top5
- 优先用 Redis，空了再回退 Memory
- 命中文本拼成 context
- context + question 拼成 Prompt
- 再调用大模型生成答案

### 同步 HTTP 问答入口
- 文件：`backend/src/main/java/com/company/aiservice/controller/ChatController.java`
- 接口：`GET /api/chat?q=...`
- 流程：`chat(...) -> ragService.answer(q)`

### 流式 WebSocket 问答入口
- 文件：`backend/src/main/java/com/company/aiservice/netty/ChatWebSocketHandler.java`
- 流程：`channelRead0(...) -> ragService.streamAnswer(question, ...)`

### RAG 检索核心流程
核心都在：
- `backend/src/main/java/com/company/aiservice/service/RagService.java`

主要步骤：
1. `answer(...)` 或 `streamAnswer(...)` 收到用户问题
2. 都先调用 `buildPrompt(question)`
3. `buildPrompt(...)` 中先读取 `knowledgeBaseStoreService.stats()`
   - 用于日志里打印当前有哪些已登记文件
4. 用 `embeddingModel.embed(question).content()` 把问题向量化
5. 先查 Redis 向量库：
   - `embeddingStoresHolder.redisStore().findRelevant(qEmbedding, 5)`
6. 再查内存向量库：
   - `embeddingStoresHolder.memoryStore().findRelevant(qEmbedding, 5)`





1. 结果选择策略：
   - **优先 Redis 命中结果**
   - **Redis 没命中时回退 Memory**
2. 把命中的片段文本拼接成 `context`
3. 再拼出最终 Prompt：
   - 包含 `[上下文]`
   - 包含 `[问题]`
   - 要求中文回答
   - 如果上下文无法回答，需要明确说明



**核心**

```java

            // 先把用户问题转成向量。
        Embedding qEmbedding = embeddingModel.embed(question).content();
            // 输出当前问题已经完成向量化的日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] 问题向量化完成，开始分别查询 Redis 与 Memory", true);
            // TODO:NOTICE 先用 Redis 向量库执行相似检索。
            List<EmbeddingMatch<TextSegment>> redisMatches = embeddingStoresHolder.redisStore().findRelevant(qEmbedding, 5);
            // 输出 Redis 命中数量日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] Redis 命中片段数: " + redisMatches.size(), true);
           
           
            // 再用内存向量库执行相似检索。
            List<EmbeddingMatch<TextSegment>> memoryMatches = embeddingStoresHolder.memoryStore().findRelevant(qEmbedding, 5);
            // 输出内存命中数量日志。
            juLog.write(Tip.MESSAGE, "[RAG检索] Memory 命中片段数: " + memoryMatches.size(), true);
          
          
            // 优先采用 Redis 命中结果；如果 Redis 没命中，再回退到内存命中结果。
            List<EmbeddingMatch<TextSegment>> matches = !redisMatches.isEmpty() ? redisMatches : memoryMatches;
         
         
            // 逐个输出最终采用的命中片段信息。
            for (int i = 0; i < matches.size(); i++) {
                // 读取当前命中的匹配对象。
                EmbeddingMatch<TextSegment> match = matches.get(i);
                // 读取当前片段文本内容。
                String matchedText = match.embedded() == null ? "" : match.embedded().text();
                // 输出当前命中片段的相似度和文本预览。
                juLog.write(Tip.MESSAGE,
                        "[RAG检索] 最终采用第 " + (i + 1) + " 片，相似度: " + match.score() + "，预览: " + preview(matchedText, 200),
                        true);
            }
            // 把检索到的文本片段拼成上下文。
        String context = matches.stream()
                .map(m -> m.embedded().text())
                .collect(Collectors.joining("\n\n"));

            
            
            // 如果知识库里没有可用片段，就返回一个无知识引用的提示词。
            if (context.isBlank()) {
                // 输出当前没有命中任何知识片段的日志。
                juLog.write(Tip.WARRING, "[RAG检索] Redis 与 Memory 都没有命中任何知识库片段", true);
                // 返回无知识库命中的提示词。
                return "你是企业知识库助手。当前知识库中还没有可用的检索片段。"
                        + "\n\n[问题]\n" + question
                        + "\n\n请使用中文回答，并明确说明当前回答未引用到知识库内容。";
            }

        return "你是企业知识库助手。结合下列检索到的上下文回答用户问题。"
                + "\n\n[上下文]\n" + context
                + "\n\n[问题]\n" + question
                + "\n\n请使用中文回答，并在无法从上下文中得出答案时明确说明。";
```



1. 最后交给模型：
   - HTTP 同步接口：`chatModel.generate(prompt)`
   - WebSocket 流式接口：`deepSeekStreamingService.streamChat(prompt, ...)`

### 检索相关关键文件

#### 1）RAG 核心
- 文件：`backend/src/main/java/com/company/aiservice/service/RagService.java`
- 建议重点阅读：
  - `answer(...)`
  - `streamAnswer(...)`
  - `buildPrompt(...)`

#### 2）向量模型与向量库配置
- 文件：`backend/src/main/java/com/company/aiservice/config/OpenAiConfig.java`
- 建议重点阅读：
  - `embeddingModel()`
  - `redisEmbeddingStore(...)`
  - `memoryEmbeddingStore(...)`
  - `documentSplitter()`

#### 3）双存储持有器
- 文件：`backend/src/main/java/com/company/aiservice/service/EmbeddingStoresHolder.java`
- 作用：统一拿到 Redis 与内存两套向量存储

### 检索流程里的关键判断
- Redis 与 Memory 都会查
- 但真正拼 Prompt 时：
  - 有 Redis 命中，就优先用 Redis
  - Redis 空，再用 Memory
- 如果 `context.isBlank()`：
  - 返回“当前回答未引用知识库内容”的提示词
- 如果检索报错：
  - 降级为普通问答，不让接口整体崩掉



## 三、 上传文件，文件怎么识别的，怎么切片的？
- 识别：`DocumentService.parse(...)` 里用 **Apache Tika** 解析文件内容/格式

- ```java
  // 把上传文件解析成统一的 Document 对象。
  private Document parse(MultipartFile file, byte[] bytes) throws IOException {
      // parse 是“文件怎么识别”的关键位置：先拿原始文件名做日志标识，但真正解析依赖 Tika 对文件内容与格式的识别能力。
      // 先取出原始文件名。
      String filename = file.getOriginalFilename();
      // 如果文件名为空，则给一个默认值。
      if (filename == null) {
          // 使用默认文件名避免空值影响后续处理。
          filename = "unknown";
      }
      try (InputStream is = new java.io.ByteArrayInputStream(bytes)) {
          // 输出当前文件开始使用 Tika 解析的日志。
          juLog.write(Tip.MESSAGE, "[知识库解析] 使用 Tika 解析文件: " + filename, true);
          //TODO:NOTCE 解析上传的文档-> 优先使用 Tika 解析结构化文档内容。
          return parser.parse(is);
      } catch (Exception e) {
          // 输出当前文件 Tika 解析失败并准备走纯文本兜底的日志。
          juLog.write(Tip.WARRING, "[知识库解析] Tika 解析失败，改为纯文本兜底: " + filename + "，原因: " + e.getMessage(), true);
          // 如果 Tika 解析失败，则把文件按 UTF-8 文本方式兜底读取。
          String text = new String(bytes, StandardCharsets.UTF_8);
          // 使用纯文本内容构造 Document，避免上传直接失败。
          return Document.from(text);
      }
  }
  ```

- 本质上底层依赖 **Apache Tika**。

  所以它的能力不是只认一种格式，而是属于：

  - 常见办公文档：大多能解析

  - 纯文本类：基本能解析

- 兜底：Tika 失败就按 **UTF-8 纯文本** 读取

- 切片：`OpenAiConfig.documentSplitter()` 中配置为 **`recursive(700, 150)`**

- 入库：每个片段都做 embedding，并写入 **Redis + Memory** 两套向量存储



### 向量存储

```java
// 遍历每一个文本片段并写入向量存储。
for (int i = 0; i < segments.size(); i++) {
    // 取出当前片段对象。
    TextSegment segment = segments.get(i);
    // 读取当前片段文本内容。
    String segmentText = segment.text();
    // 截断当前片段预览文本，避免日志过长。
    String preview = segmentText == null ? "" : segmentText.substring(0, Math.min(segmentText.length(), 120)).replace("\n", " ");
    // 输出当前片段序号和预览内容，方便观察切片方式。
    juLog.write(Tip.MESSAGE, "[知识库切片] 第 " + (i + 1) + " 片长度: " + (segmentText == null ? 0 : segmentText.length()) + "，预览: " + preview, true);
    // TODO:NOTICE 先把文本片段转成 embedding 向量。
    Embedding embedding = embeddingModel.embed(segment).content();
    // 输出当前片段已经完成向量化的日志。
    juLog.write(Tip.MESSAGE, "[向量写入] 第 " + (i + 1) + " 片开始双写向量存储", true);
    // TODO:NOTICE 先把向量和原始片段写入 Redis 向量存储，并记录返回的文档 id。
    String redisDocumentId = embeddingStoresHolder.redisStore().add(embedding, segment);
    // 输出当前 Redis 写入返回的文档 id，方便定位实际写入的 key。
    juLog.write(Tip.MESSAGE, "[向量写入] 第 " + (i + 1) + " 片 Redis 返回文档ID: " + redisDocumentId, true);
    // 再把向量和原始片段写入内存向量存储。
    embeddingStoresHolder.memoryStore().add(embedding, segment);
    // 输出当前片段已经双写完成的日志。
    juLog.write(Tip.MESSAGE, "[向量写入] 第 " + (i + 1) + " 片双写完成", true);
    // 立刻用当前片段自己的向量对 Redis 做一次回查自检。
    try {
        // 执行 Redis 写后即查自检，方便观察 Redis 检索链路是否可用。
        List<EmbeddingMatch<TextSegment>> redisMatches = embeddingStoresHolder.redisStore().findRelevant(embedding, 1);
        // 输出当前片段 Redis 写后即查的命中数日志。
        juLog.write(Tip.MESSAGE, "[向量自检] 第 " + (i + 1) + " 片 Redis 写后即查命中数: " + redisMatches.size(), true);
        // 如果 Redis 写后即查有结果，则输出 Redis 回查结果预览。
        if (!redisMatches.isEmpty() && redisMatches.get(0).embedded() != null) {
            // 输出当前 Redis 回查结果的相似度和文本预览。
            juLog.write(Tip.MESSAGE,
                    "[向量自检] 第 " + (i + 1) + " 片 Redis 回查相似度: " + redisMatches.get(0).score()
                            + "，预览: " + preview(redisMatches.get(0).embedded().text(), 120),
                    true);
        }
    } catch (Exception e) {
        // 如果 Redis 自检失败，则只记录日志，不影响上传主流程。
        juLog.write(Tip.WARRING,
                "[向量自检] 第 " + (i + 1) + " 片 Redis 写后即查失败，不影响上传: " + e.getMessage(),
                true);
    }
    // 立刻用当前片段自己的向量对内存库做一次回查自检。
    List<EmbeddingMatch<TextSegment>> memoryMatches = embeddingStoresHolder.memoryStore().findRelevant(embedding, 1);
    // 输出当前片段内存写后即查的命中数日志。
    juLog.write(Tip.MESSAGE, "[向量自检] 第 " + (i + 1) + " 片 Memory 写后即查命中数: " + memoryMatches.size(), true);
    // 如果内存写后即查有结果，则输出内存回查结果预览。
    if (!memoryMatches.isEmpty() && memoryMatches.get(0).embedded() != null) {
        // 输出当前内存回查结果的相似度和文本预览。
        juLog.write(Tip.MESSAGE,
                "[向量自检] 第 " + (i + 1) + " 片 Memory 回查相似度: " + memoryMatches.get(0).score()
                        + "，预览: " + preview(memoryMatches.get(0).embedded().text(), 120),
                true);
    }
}
```

## 四. Netty 流程怎么走的？

> ​	总体链路
>
> 1. Spring Boot 启动后，`NettyWebSocketConfig.start()` 会额外拉起一个后台线程
> 2. 后台线程执行 `runServer()`，监听 `8083`
> 3. Netty pipeline 依次装配：
>    - `HttpServerCodec`
>    - `HttpObjectAggregator`
>    - `ChunkedWriteHandler`
>    - `WebSocketServerProtocolHandler`
>    - `ChatWebSocketHandler`
> 4. 前端连接 `ws://127.0.0.1:8083/ws/chat`
> 5. 握手成功后，`ChatWebSocketHandler.userEventTriggered(...)` 发送 `system` 消息
> 6. 前端收到 `system` 后，发送：
>    - `{"type":"user","data":"用户问题"}`
> 7. `ChatWebSocketHandler.channelRead0(...)` 解析 JSON、校验消息
> 8. 之后调用 `ragService.streamAnswer(...)`
> 9. `RagService` 再调用 `DeepSeekStreamingService.streamChat(...)`
> 10. DeepSeek SSE 每来一段 token，就转成 WebSocket `chunk` 消息返回前端
> 11. 完成后发送 `done`
> 12. 异常时发送 `error`

- Spring 启动 -> `NettyWebSocketConfig.start()` 拉起 Netty 线程
- 监听 `8083`，路径 `/ws/chat`



处理器链

- pipeline：`HttpServerCodec -> HttpObjectAggregator -> ChunkedWriteHandler -> WebSocketServerProtocolHandler -> ChatWebSocketHandler`

```
先把底层网络字节流解析成 HTTP
再把零散 HTTP 包拼成完整请求
再准备好大块内容的写出能力
再把 HTTP 升级成 WebSocket 协议
最后交给你自己的聊天处理器处理
```



- 前端连接后收到 `system`
- 前端发 `user` 消息
- 后端调用 `RagService.streamAnswer(...)`
- DeepSeek 返回 token 时逐段推送 `chunk`
- 完成发 `done`，失败发 `error`



前端：

```javascript
//TODO:NOTICE 后端传过来的JSON格式拆分
const handleWsMessage = (raw) => {
  let message
  try {
    message = JSON.parse(String(raw || ''))
  } catch {
    chatError.value = '收到无法解析的 WebSocket 消息'
    isAsking.value = false
    closeSocket('协议错误')
    return
  }

  if (message.type === 'system') {
    wsStatus.value = '已连接'
    sendQuestion()
    return
  }

  if (message.type === 'chunk') {
    streamBuffer.value += message.data || ''
    return
  }

  if (message.type === 'done') {
    answer.value = streamBuffer.value
    isAsking.value = false
    pushFeed('问答完成', 'WebSocket JSON 流式回答已结束。', 'success')
    closeSocket('已完成')
    return
  }

  if (message.type === 'error') {
    chatError.value = message.data || '问答失败'
    isAsking.value = false
    pushFeed('问答失败', chatError.value, 'danger')
    closeSocket('异常')
  }
}

const submitQuestion = () => {
  // 读取并清洗用户输入的问题文本；如果是空串就不发起任何请求。
  const q = question.value.trim()
  if (!q) return

  // 每次发起新一轮提问前，先清空旧答案、旧流缓冲和旧错误，避免界面混入上一次会话状态。
  answer.value = ''
  streamBuffer.value = ''
  chatError.value = ''
  // 标记当前进入“问答中”状态，并先关闭旧连接，随后准备重新建立新的 WebSocket 会话。
  isAsking.value = true
  closeSocket('连接中')

  try {
    // 真正创建到 Netty `/ws/chat` 的 WebSocket 连接；后续 onopen/onmessage/onerror/onclose 都围绕这个连接展开。
    chatSocket = new WebSocket(wsBase.value)
  } catch (error) {
    // 如果连浏览器侧创建连接都失败，则直接恢复状态并提示前端，不进入后续问答流程。
    chatError.value = error.message || 'WebSocket 连接失败'
    isAsking.value = false
    wsStatus.value = '连接失败'
    return
  }

  chatSocket.onopen = () => {
    // onopen 只代表底层连接建立完成；真正发送问题要等后端握手完成后返回 `system` 消息再触发。
    wsStatus.value = '握手完成'
    pushFeed('问答开始', '已通过 Netty WebSocket JSON 协议发起流式问答。', 'info')
  }

  chatSocket.onmessage = (event) => {
    // 所有后端返回的 system/chunk/done/error 都统一交给 handleWsMessage 解析和分发处理。
    handleWsMessage(event.data)
  }

  chatSocket.onerror = () => {
    // 连接异常时立即结束当前问答态，提示用户检查 Netty 服务是否正常启动。
    chatError.value = 'WebSocket 通道异常，请检查 Netty 服务是否启动。'
    isAsking.value = false
    pushFeed('连接异常', chatError.value, 'danger')
    closeSocket('异常')
  }

  chatSocket.onclose = () => {
    // 如果连接在问答中途被关闭，则把界面状态切回断开，避免页面一直显示“生成中”。
    if (isAsking.value) {
      isAsking.value = false
      wsStatus.value = '已断开'
    }
  }
}

```

后端

```java
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

```



```java
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

```





