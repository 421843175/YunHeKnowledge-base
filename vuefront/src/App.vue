<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

const apiBase = ref('http://127.0.0.1:8082')
const wsBase = ref('ws://127.0.0.1:8083/ws/chat')
const modelName = ref('deepseek-chat')
const question = ref('')
const selectedFiles = ref([])
const answer = ref('')
const streamBuffer = ref('')
const uploadMessage = ref('')
const uploadError = ref('')
const chatError = ref('')
const isUploading = ref(false)
const isAsking = ref(false)
const wsStatus = ref('未连接')
const knowledgeStats = reactive({
  files: 0,
  lastSegments: 0,
  totalSegments: 0,
})

const activityFeed = ref([
  {
    title: '系统就绪',
    text: '前端已切换为企业知识库 RAG 页面，聊天改为 Netty WebSocket JSON 协议。',
    level: 'success',
    time: '刚刚',
  },
  {
    title: '模型默认值',
    text: '当前默认展示 DeepSeek 大模型，可直接用于问答入口。',
    level: 'info',
    time: '刚刚',
  },
])

let chatSocket = null

const acceptedFormatsText = computed(() => {
  if (!selectedFiles.value.length) return '支持上传 doc / docx / pdf 文档'
  return selectedFiles.value.map((file) => file.name).join('、')
})

const canUpload = computed(() => selectedFiles.value.length > 0 && !isUploading.value)
const canAsk = computed(() => question.value.trim().length > 0 && !isAsking.value)
const previewAnswer = computed(() => streamBuffer.value || answer.value)

const pushFeed = (title, text, level = 'info') => {
  activityFeed.value.unshift({
    title,
    text,
    level,
    time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
  })
  activityFeed.value = activityFeed.value.slice(0, 8)
}

const applyKnowledgeStats = (data) => {
  // 优先读取后端返回的累计文件数。
  knowledgeStats.files = Number(data?.uploadedFilesCount ?? data?.totalFiles ?? 0)
  // 优先读取后端返回的累计向量片段数。
  knowledgeStats.totalSegments = Number(data?.segmentsIndexedTotal ?? data?.totalSegments ?? 0)
  // 如果后端有本次切分数就显示本次值，否则刷新时默认显示 0。
  knowledgeStats.lastSegments = Number(data?.segmentsIndexed ?? 0)
}

const loadKnowledgeStats = async () => {
  try {
    // 页面初始化时主动读取一次知识库统计信息。
    const response = await fetch(`${apiBase.value}/api/docs/stats`)
    // 如果状态接口返回失败，则直接抛错进入异常分支。
    if (!response.ok) {
      throw new Error(`读取知识库状态失败，状态码 ${response.status}`)
    }
    // 解析后端返回的 JSON 统计结果。
    const data = await response.json()
    // 将统计结果同步到页面状态中。
    applyKnowledgeStats(data)
  } catch (error) {
    // 如果读取状态失败，则仅记录动态，不打断页面使用。
    pushFeed('状态读取失败', error.message || '知识库状态读取失败', 'warn')
  }
}

const handleFileChange = (event) => {
  const files = Array.from(event.target.files || [])
  selectedFiles.value = files
  uploadMessage.value = files.length ? `已选择 ${files.length} 个文件` : ''
  uploadError.value = ''

  if (files.length) {
    pushFeed('文件已选择', `待上传：${files.map((file) => file.name).join('、')}`, 'info')
  }
}

const uploadDocuments = async () => {
  if (!selectedFiles.value.length) return

  isUploading.value = true
  uploadError.value = ''
  uploadMessage.value = ''

  const formData = new FormData()
  selectedFiles.value.forEach((file) => formData.append('files', file))

  try {
    const response = await fetch(`${apiBase.value}/api/docs/upload`, {
      method: 'POST',
      body: formData,
    })

    if (!response.ok) {
      throw new Error(`上传失败，状态码 ${response.status}`)
    }

    const data = await response.json()
    // 先用后端上传返回结果立即刷新页面统计。
    applyKnowledgeStats(data)
    uploadMessage.value = `上传成功，已切分 ${knowledgeStats.lastSegments} 个片段`
    pushFeed('文档入库成功', `本次向量化 ${knowledgeStats.lastSegments} 个片段，累计 ${knowledgeStats.totalSegments} 个片段。`, 'success')
    // 上传完成后再次主动拉取状态，确保刷新逻辑与后端最终结果一致。
    await loadKnowledgeStats()
  } catch (error) {
    uploadError.value = error.message || '上传失败'
    pushFeed('文档上传失败', uploadError.value, 'danger')
  } finally {
    isUploading.value = false
  }
}

const closeSocket = (nextStatus = '未连接') => {
  if (chatSocket) {
    const socket = chatSocket
    chatSocket = null
    socket.close()
  }
  wsStatus.value = nextStatus
}

const sendQuestion = () => {
  const payload = {
    type: 'user',
    data: question.value.trim(),
  }
  chatSocket?.send(JSON.stringify(payload))
}

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

const stopAnswer = () => {
  if (streamBuffer.value && !answer.value) {
    answer.value = streamBuffer.value
  }
  closeSocket('已停止')
  isAsking.value = false
  pushFeed('已停止输出', '当前 WebSocket 会话已手动关闭。', 'warn')
}

onMounted(() => {
  // 页面首次加载时主动读取历史知识库统计信息。
  loadKnowledgeStats()
})

onBeforeUnmount(() => {
  closeSocket()
})
</script>

<template>
  <div class="app-shell">
    <div class="glow glow-left"></div>
    <div class="glow glow-right"></div>

    <header class="hero panel">
      <div class="hero-copy">
        <p class="eyebrow">云核 ENTERPRISE SERVICE / LANGCHAIN4J + RAG</p>
        <h1>云核 企业知识库问答平台</h1>
        <p class="hero-text">
          上传企业内部文档，后端通过 LangChain4j 完成解析、切分、向量化与检索增强，
          再由 DeepSeek 大模型基于检索上下文进行中文问答。聊天通道已切换为 Netty WebSocket JSON 协议。
        </p>
        <div class="hero-tags">
          <span>DeepSeek</span>
          <span>LangChain4j</span>
          <span>RAG</span>
          <span>Netty WebSocket</span>
        </div>
      </div>

      <div class="hero-side panel-lite">
        <p class="panel-kicker">当前接入</p>
        <div class="hero-model">{{ modelName }}</div>
        <div class="hero-lines">
          <div>
            <span>HTTP 后端</span>
            <strong>{{ apiBase }}</strong>
          </div>
          <div>
            <span>WebSocket 通道</span>
            <strong>{{ wsBase }}</strong>
          </div>
        </div>
      </div>
    </header>

    <main class="layout-grid">
      <section class="left-column">
        <article class="panel card upload-card">
          <div class="section-head">
            <div>
              <p class="section-kicker">STEP 1</p>
              <h2>上传企业文档</h2>
            </div>
            <span class="pill">/api/docs/upload</span>
          </div>

          <p class="card-desc">
            支持把企业制度、FAQ、培训材料、说明书等文档发给后端，自动进入 RAG 检索语料。
          </p>

          <label class="upload-zone">
            <input type="file" multiple accept=".doc,.docx,.pdf" @change="handleFileChange" />
            <strong>选择文档文件</strong>
            <span>{{ acceptedFormatsText }}</span>
          </label>

          <div class="upload-actions">
            <button class="primary-btn" :disabled="!canUpload" @click="uploadDocuments">
              {{ isUploading ? '上传中...' : '上传并生成向量片段' }}
            </button>
          </div>

          <p v-if="uploadMessage" class="feedback success-text">{{ uploadMessage }}</p>
          <p v-if="uploadError" class="feedback danger-text">{{ uploadError }}</p>
        </article>

        <article class="panel card stats-card">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">RAG STATUS</p>
              <h2>知识库状态</h2>
            </div>
          </div>

          <div class="stats-grid">
            <div class="stat-box">
              <span>已上传文件数</span>
              <strong>{{ knowledgeStats.files }}</strong>
            </div>
            <div class="stat-box">
              <span>累计切分片段</span>
              <strong>{{ knowledgeStats.lastSegments }}</strong>
            </div>
            <div class="stat-box accent-box">
              <span>累计向量片段</span>
              <strong>{{ knowledgeStats.totalSegments }}</strong>
            </div>
          </div>
        </article>

        <article class="panel card feed-card">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">LIVE FEED</p>
              <h2>系统动态</h2>
            </div>
          </div>

          <div class="feed-list">
            <article v-for="item in activityFeed" :key="item.title + item.time" class="feed-item" :data-level="item.level">
              <div class="feed-top">
                <strong>{{ item.title }}</strong>
                <span>{{ item.time }}</span>
              </div>
              <p>{{ item.text }}</p>
            </article>
          </div>
        </article>
      </section>

      <section class="right-column">
        <article class="panel card ask-card">
          <div class="section-head">
            <div>
              <p class="section-kicker">STEP 2</p>
              <h2>向 AI 提问</h2>
            </div>
            <span class="pill">Netty /ws/chat · JSON</span>
          </div>

          <div class="field-grid two-rows">
            <label class="field field-wide">
              <span>HTTP 后端地址</span>
              <input v-model="apiBase" placeholder="http://127.0.0.1:8082" />
            </label>

            <label class="field field-wide">
              <span>WebSocket 地址</span>
              <input v-model="wsBase" placeholder="ws://127.0.0.1:8083/ws/chat" />
            </label>
          </div>

          <label class="field field-wide question-field">
            <span>问题内容</span>
            <textarea
              v-model="question"
              rows="7"
              placeholder="例如：根据我上传的员工手册，试用期转正流程是什么？"
            ></textarea>
          </label>

          <div class="ask-actions">
            <button class="primary-btn" :disabled="!canAsk" @click="submitQuestion">
              {{ isAsking ? 'AI 思考中...' : '开始问答' }}
            </button>
            <button class="secondary-btn" :disabled="!isAsking" @click="stopAnswer">
              停止输出
            </button>
          </div>

          <p class="feedback">当前通道状态：{{ wsStatus }}</p>
          <p v-if="chatError" class="feedback danger-text">{{ chatError }}</p>
        </article>

        <article class="panel card answer-card">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">AI ANSWER</p>
              <h2>回答结果</h2>
            </div>
            <span class="state-text">{{ isAsking ? '生成中' : '已就绪' }}</span>
          </div>

          <div class="answer-surface" :class="{ streaming: isAsking }">
            <p v-if="previewAnswer">{{ previewAnswer }}</p>
            <p v-else class="placeholder-text">
              这里会显示 DeepSeek 基于 RAG 检索上下文通过 WebSocket JSON 协议返回的中文答案。
            </p>
          </div>
        </article>
      </section>
    </main>
  </div>
</template>
