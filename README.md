# AI企业服务

这是一个基于 `Spring Boot + Redis + LangChain4j + Netty + Vue 3` 的企业知识库问答示例项目。

项目主要包含两部分：

- `backend/`：后端服务，负责文档上传、解析、切片、向量化、RAG 检索和 AI 问答
- `vuefront/`：前端页面，负责文件上传、知识库状态展示和 WebSocket 流式问答

如果你只是想快速了解项目结构和阅读路径，可以先看根目录下的 `NOTICE.md`。

---

## 1. 项目功能

当前项目支持以下核心能力：

- 上传企业文档到知识库
- 使用 Apache Tika 解析文档内容
- 按固定规则切片并生成向量
- 向量写入 Redis 和内存存储
- 用户提问时进行 RAG 检索
- 通过 DeepSeek 生成中文回答
- 通过 Netty WebSocket 向前端流式返回回答结果

当前更适合处理：

- `pdf`（文字型）
- `doc` / `docx`
- `ppt` / `pptx`
- `md` / `txt` 等文本类文件

当前没有额外实现：

- OCR 图片识别
- 扫描版 PDF OCR
- 音视频转文本
- 用户历史聊天记忆

---

## 2. 技术栈

### 后端
- Java 17
- Spring Boot 3
- LangChain4j
- Redis
- Netty
- OkHttp SSE
- Apache Tika

### 前端
- Vue 3
- Vite

### 模型与向量能力
- 聊天模型：DeepSeek
- Embedding 模型：智谱 `embedding-3`

---

## 3. 目录结构

```text
AI企业服务/
├─ backend/                     # Spring Boot 后端
│  ├─ src/main/java/            # Java 源码
│  ├─ src/main/resources/       # 配置文件
│  ├─ backend/data/uploads/     # 上传文件落盘目录
│  ├─ backend/data/knowledge-metadata.json
│  └─ pom.xml
├─ vuefront/                    # Vue 前端
│  ├─ src/
│  ├─ package.json
│  └─ vite.config.js
├─ NOTICE.md                    # 项目辅助阅读说明
└─ README.md                    # 当前说明文件
```

---

## 4. 核心流程概览

### 4.1 文档上传入库流程
1. 前端调用 `POST /api/docs/upload`
2. 后端 `DocController.upload(...)` 接收文件
3. `DocumentService.indexFile(...)` 处理单个文件
4. 使用 Apache Tika 解析文档
5. 使用 `recursive(700, 150)` 规则切片
6. 对每个片段生成 embedding
7. 写入 Redis 和内存向量库
8. 原始文件保存到本地，元信息写入 `knowledge-metadata.json`

### 4.2 用户提问检索流程
1. 前端通过 HTTP 或 WebSocket 发送问题
2. `RagService.buildPrompt(...)` 先把问题向量化
3. 先查 Redis 向量库，再查内存向量库
4. 优先使用 Redis 命中结果，空了再回退内存结果
5. 将命中的文本片段拼接成上下文
6. 把上下文和用户问题一起交给大模型
7. 返回答案给前端

### 4.3 Netty WebSocket 流式流程
1. Netty 启动并监听 `/ws/chat`
2. 前端建立 WebSocket 连接
3. 握手完成后后端发 `system` 消息
4. 前端收到 `system` 后发送 `user` 消息
5. 后端调用 `RagService.streamAnswer(...)`
6. DeepSeek SSE 流式返回 token
7. 后端把 token 转成 `chunk` 消息推给前端
8. 完成后发 `done`，异常时发 `error`

---

## 5. 关键文件

如果只想快速抓主线，建议优先看这些文件：

- `backend/src/main/resources/application.yml`
- `backend/src/main/java/com/company/aiservice/config/OpenAiConfig.java`
- `backend/src/main/java/com/company/aiservice/controller/DocController.java`
- `backend/src/main/java/com/company/aiservice/service/DocumentService.java`
- `backend/src/main/java/com/company/aiservice/service/RagService.java`
- `backend/src/main/java/com/company/aiservice/config/NettyWebSocketConfig.java`
- `backend/src/main/java/com/company/aiservice/netty/ChatWebSocketHandler.java`
- `backend/src/main/java/com/company/aiservice/service/DeepSeekStreamingService.java`
- `vuefront/src/App.vue`
- `NOTICE.md`

---

## 6. 默认配置

当前源码中的默认配置如下：

- 后端 HTTP：`http://127.0.0.1:8082`
- WebSocket：`ws://127.0.0.1:8083/ws/chat`
- 上传目录：`backend/data/uploads`
- 元信息文件：`backend/data/knowledge-metadata.json`

请注意：

- 当前 `application.yml` 中包含模型 API Key 和 Redis 连接信息
- 如果用于正式环境，建议改为环境变量或私有配置文件，不要直接写死在仓库中

---

## 7. 本地启动方式

### 7.1 启动后端
在 `backend/` 目录执行：

```bash
mvn spring-boot:run
```

或先打包再运行：

```bash
mvn clean package
java -jar target/ai-enterprise-service-0.0.1-SNAPSHOT.jar
```

### 7.2 启动前端
node22

在 `vuefront/` 目录执行：

```bash
npm install
npm run dev
```

前端启动后，根据控制台输出访问对应地址即可。

---

## 8. 接口说明

### 8.1 上传文件
- 方法：`POST`
- 地址：`/api/docs/upload`
- 参数：`files`，支持多文件上传

### 8.2 查询知识库状态
- 方法：`GET`
- 地址：`/api/docs/stats`

### 8.3 HTTP 同步问答
- 方法：`GET`
- 地址：`/api/chat?q=你的问题`

### 8.4 WebSocket 流式问答
- 地址：`ws://127.0.0.1:8083/ws/chat`

前后端约定的消息格式为：

```json
{
  "type": "user",
  "data": "用户问题"
}
```

后端返回的消息类型主要有：

- `system`
- `chunk`
- `done`
- `error`

---

## 9. 已知设计特点

### 9.1 双向量存储
项目当前会把 embedding 同时写入：

- Redis 向量库
- 内存向量库

检索时优先用 Redis，Redis 没命中时回退到内存。

### 9.2 当前是单轮问答
项目当前没有保存用户历史聊天上下文。

也就是说现在问答只依赖：

- 当前用户问题
- 当前召回的知识库片段

### 9.3 更偏向阅读与演示型工程
从当前实现来看，这个项目更适合作为：

- 企业知识库 RAG 示例
- Netty WebSocket 流式问答示例
- Spring Boot + Vue 的整合参考

---

## 10. 阅读建议

如果你是后来接手这个项目的人，建议先阅读：

- `README.md`：了解项目整体用途与启动方式
- `NOTICE.md`：了解项目辅助阅读路径和关键链路

如果你想进一步熟悉代码，建议按以下主线阅读：

1. 上传链路：`DocController -> DocumentService -> KnowledgeBaseStoreService`
2. 问答链路：`ChatController / ChatWebSocketHandler -> RagService`
3. 流式链路：`NettyWebSocketConfig -> ChatWebSocketHandler -> DeepSeekStreamingService`
4. 模型与存储配置：`OpenAiConfig`

---

## 11. 补充说明

根目录下的 `NOTICE.md` 已经整理成“项目辅助阅读说明”，更适合给后来阅读代码的人快速上手。

如果后续需要更细的说明，可以继续补充：

- 方法级阅读地图
- 文件 + 行号索引
- 上传 / 检索 / WebSocket 时序图

