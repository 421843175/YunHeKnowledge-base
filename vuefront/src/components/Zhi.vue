<script setup>
import TopNav from './TopNav.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { getProfile, getToken } from '../utils/token'

const apiBase = ref('http://127.0.0.1:8082')
const wsBase = ref(
  `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://127.0.0.1:8083/ws/chat`
)
const modelName = ref('deepseek-chat')
const question = ref('')
const selectedFiles = ref([])
const answer = ref('')
const streamBuffer = ref('')
const uploadMessage = ref('')
const uploadError = ref('')
const fileDialogError = ref('')
const chatError = ref('')
const chatHistoryError = ref('')
const historyKeyword = ref('')
const chatHistory = ref([])
const isLoadingHistory = ref(false)
const isUploading = ref(false)
const isAsking = ref(false)
const isFileDialogOpen = ref(false)
const isLoadingFiles = ref(false)
const deletingFileName = ref('')
const managedFiles = ref([])
const wsStatus = ref('就绪')
const knowledgeStats = reactive({
  files: 0,
  lastSegments: 0,
  totalSegments: 0,
})
let feedSeed = 0

const createFeedItem = (title, text, level = 'info') => ({
  id: `${Date.now()}-${feedSeed++}`,
  title,
  text,
  level,
  time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
})

const activityFeed = ref([
  {
    id: 'boot-ready',
    title: '系统就绪',
    text: '前端已切换为企业知识库 RAG 页面，聊天改为 Netty WebSocket JSON 协议。',
    level: 'success',
    time: '刚刚',
  },
  {
    id: 'boot-model',
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
const themeOptions = [
  { label: '夜幕', value: 'dark' },
  { label: '晨光', value: 'light' },
]
const theme = ref(localStorage.getItem('zhi-theme') || 'dark')
const isLoggedIn = computed(() => Boolean(getToken()))
const currentProfile = computed(() => getProfile())
const decodeTokenPayload = (token) => {
  try {
    const parts = String(token || '').split('.')
    if (parts.length < 2) return null
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(decodeURIComponent(escape(window.atob(payload))))
  } catch {
    return null
  }
}
const tokenPayload = computed(() => decodeTokenPayload(getToken()))
const currentRole = computed(() => Number(currentProfile.value?.role ?? tokenPayload.value?.role ?? 2))
const currentEnterpriseId = computed(() => Number(currentProfile.value?.enterpriseId ?? tokenPayload.value?.enterprise_id ?? 0))
const canManageDocs = computed(() => isLoggedIn.value && currentRole.value !== 2)

const applyTheme = (value) => {
  const nextTheme = value === 'light' ? 'light' : 'dark'
  theme.value = nextTheme
  document.documentElement.setAttribute('data-theme', nextTheme)
  document.body.setAttribute('data-theme', nextTheme)
  localStorage.setItem('zhi-theme', nextTheme)
}

const toggleTheme = () => {
  applyTheme(theme.value === 'dark' ? 'light' : 'dark')
}

const currentThemeLabel = computed(
  () => themeOptions.find((item) => item.value === theme.value)?.label || '夜幕'
)

const buildAuthHeaders = () => {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

const pushFeed = (title, text, level = 'info') => {
  activityFeed.value.unshift(createFeedItem(title, text, level))
  activityFeed.value = activityFeed.value.slice(0, 8)
}

const formatHistoryTime = (value) => {
  if (!value) return '未知时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return String(value).replace('T', ' ').split('.')[0]
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

const summarizeHistoryText = (text, maxLength = 52) => {
  const normalized = String(text || '').replace(/\s+/g, ' ').trim()
  if (!normalized) return '暂无内容'
  return normalized.length > maxLength ? `${normalized.slice(0, maxLength)}…` : normalized
}

const expandHistoryItem = (item) => {
  const questionText = String(item?.question || '').trim()
  const answerText = String(item?.answer || '').trim()
  return {
    ...item,
    formattedTime: formatHistoryTime(item?.createdAt),
    previewQuestion: summarizeHistoryText(questionText, 34),
    previewAnswer: summarizeHistoryText(answerText, 48),
    questionText,
    answerText,
  }
}

const loadChatHistory = async () => {
  if (!isLoggedIn.value) return
  chatHistoryError.value = ''
  isLoadingHistory.value = true
  try {
    const query = historyKeyword.value.trim()
      ? `?keyword=${encodeURIComponent(historyKeyword.value.trim())}`
      : ''
    const response = await fetch(`${apiBase.value}/api/chat/history${query}`, {
      headers: buildAuthHeaders(),
    })
    if (!response.ok) {
      throw new Error(`读取聊天历史失败，状态码 ${response.status}`)
    }
    chatHistory.value = (await response.json()).map(expandHistoryItem)
  } catch (error) {
    chatHistoryError.value = error.message || '读取聊天历史失败'
  } finally {
    isLoadingHistory.value = false
  }
}

const saveChatHistory = async (questionText, answerText) => {
  try {
    const response = await fetch(`${apiBase.value}/api/chat/history`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...buildAuthHeaders(),
      },
      body: JSON.stringify({ question: questionText, answer: answerText }),
    })
    if (!response.ok) {
      throw new Error(`保存聊天历史失败，状态码 ${response.status}`)
    }
    const data = await response.json()
    if (Array.isArray(data?.items)) {
      chatHistory.value = data.items.map(expandHistoryItem)
    } else {
      await loadChatHistory()
    }
  } catch (error) {
    chatHistoryError.value = error.message || '保存聊天历史失败'
  }
}

const applyKnowledgeStats = (data) => {
  // 优先读取后端返回的累计文件数。
  knowledgeStats.files = Number(data?.uploadedFilesCount ?? data?.totalFiles ?? 0)
  // 优先读取后端返回的累计向量片段数。
  knowledgeStats.totalSegments = Number(data?.segmentsIndexedTotal ?? data?.totalSegments ?? 0)
  // 如果后端有本次切分数就显示本次值，否则刷新时默认显示 0。
  knowledgeStats.lastSegments = Number(data?.segmentsIndexed ?? 0)
  if (Array.isArray(data?.uploadedFiles)) {
    managedFiles.value = data.uploadedFiles
  }
  if (Array.isArray(data?.files)) {
    managedFiles.value = data.files
  }
}

const loadManagedFiles = async () => {
  fileDialogError.value = ''
  isLoadingFiles.value = true
  try {
    const response = await fetch(`${apiBase.value}/api/docs/files`, {
      headers: buildAuthHeaders(),
    })
    if (!response.ok) {
      throw new Error(`读取文件列表失败，状态码 ${response.status}`)
    }
    managedFiles.value = await response.json()
  } catch (error) {
    fileDialogError.value = error.message || '读取文件列表失败'
  } finally {
    isLoadingFiles.value = false
  }
}

const openFileDialog = async () => {
  isFileDialogOpen.value = true
  await loadManagedFiles()
}

const closeFileDialog = () => {
  isFileDialogOpen.value = false
  fileDialogError.value = ''
  deletingFileName.value = ''
}

const downloadManagedFile = async (file) => {
  fileDialogError.value = ''
  try {
    const response = await fetch(`${apiBase.value}/api/docs/download?storedFilename=${encodeURIComponent(file.storedFilename)}`, {
      headers: buildAuthHeaders(),
    })
    if (!response.ok) {
      throw new Error(`下载失败，状态码 ${response.status}`)
    }
    const blob = await response.blob()
    const blobUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = blobUrl
    link.download = file.originalFilename || file.storedFilename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(blobUrl)
  } catch (error) {
    fileDialogError.value = error.message || '下载文件失败'
  }
}

const deleteManagedFile = async (file) => {
  if (!window.confirm(`确认删除文件《${file.originalFilename || file.storedFilename}》吗？`)) return

  deletingFileName.value = file.storedFilename
  fileDialogError.value = ''
  try {
    const response = await fetch(`${apiBase.value}/api/docs/file?storedFilename=${encodeURIComponent(file.storedFilename)}`, {
      method: 'DELETE',
      headers: buildAuthHeaders(),
    })
    if (!response.ok) {
      throw new Error(`删除失败，状态码 ${response.status}`)
    }
    const data = await response.json()
    applyKnowledgeStats(data)
    pushFeed('文件已删除', `已删除：${file.originalFilename || file.storedFilename}`, 'warn')
    await loadManagedFiles()
    await loadKnowledgeStats()
  } catch (error) {
    fileDialogError.value = error.message || '删除文件失败'
  } finally {
    deletingFileName.value = ''
  }
}

const loadKnowledgeStats = async () => {
  try {
    // 页面初始化时主动读取一次知识库统计信息。
    const response = await fetch(`${apiBase.value}/api/docs/stats`, {
      headers: buildAuthHeaders(),
    })
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
  if (!selectedFiles.value.length || !canManageDocs.value) return

  isUploading.value = true
  uploadError.value = ''
  uploadMessage.value = ''

  const formData = new FormData()
  selectedFiles.value.forEach((file) => formData.append('files', file))

  try {
    const response = await fetch(`${apiBase.value}/api/docs/upload`, {
      method: 'POST',
      headers: buildAuthHeaders(),
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

const closeSocket = (nextStatus = '就绪') => {
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
    saveChatHistory(question.value.trim(), answer.value)
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
  if (!currentEnterpriseId.value) {
    chatError.value = '缺少企业信息，暂时无法连接企业知识库。'
    return
  }

  // 每次发起新一轮提问前，先清空旧答案、旧流缓冲和旧错误，避免界面混入上一次会话状态。
  answer.value = ''
  streamBuffer.value = ''
  chatError.value = ''
  // 标记当前进入“问答中”状态，并先关闭旧连接，随后准备重新建立新的 WebSocket 会话。
  isAsking.value = true
  closeSocket('连接中')

  try {
    // 真正创建到 Netty `/ws/chat` 的 WebSocket 连接；后续 onopen/onmessage/onerror/onclose 都围绕这个连接展开。
    chatSocket = new WebSocket(`${wsBase.value}?enterpriseId=${currentEnterpriseId.value}`)
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
    chatError.value = ''
    pushFeed('问答开始', `已连接企业 ${currentEnterpriseId.value} 的 WebSocket 问答通道。`, 'info')
  }

  chatSocket.onmessage = (event) => {
    // 所有后端返回的 system/chunk/done/error 都统一交给 handleWsMessage 解析和分发处理。
    handleWsMessage(event.data)
  }

  chatSocket.onerror = (event) => {
    // 连接异常时立即结束当前问答态，提示用户检查 Netty 服务是否正常启动。
    console.error('WebSocket error', event, wsBase.value, currentEnterpriseId.value)
    chatError.value = `WebSocket 通道异常：${wsBase.value}?enterpriseId=${currentEnterpriseId.value}`
    isAsking.value = false
    pushFeed('连接异常', chatError.value, 'danger')
    closeSocket('异常')
  }

  chatSocket.onclose = (event) => {
    // 如果连接在问答中途被关闭，则把界面状态切回断开，避免页面一直显示“生成中”。
    if (isAsking.value) {
      isAsking.value = false
      wsStatus.value = `已断开(${event.code})`
      if (!streamBuffer.value && !answer.value) {
        chatError.value = `WebSocket 已关闭，关闭码 ${event.code}`
      }
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
  applyTheme(theme.value)
  // 页面首次加载时主动读取历史知识库统计信息。
  loadKnowledgeStats()
  loadChatHistory()
})

watch(theme, (value) => {
  if (typeof document === 'undefined') return
  document.documentElement.setAttribute('data-theme', value)
  document.body.setAttribute('data-theme', value)
})

onBeforeUnmount(() => {
  closeSocket()
})
</script>

<template>
  <div class="site-shell" :data-theme="theme">
    <div class="site-bg-layer"></div>
    <div class="site-aurora site-aurora-left"></div>
    <div class="site-aurora site-aurora-right"></div>
    <TopNav />
    <div class="app-shell zhi-page">
    <div class="glow glow-left"></div>
    <div class="glow glow-right"></div>

    <header class="hero panel">
      <div class="hero-copy">
        <p class="eyebrow">云核心 ENTERPRISE SERVICE / LANGCHAIN4J + RAG</p>
        <h1>云核心 企业知识库问答平台</h1>
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
        <div class="theme-switcher">
          <span class="theme-caption">当前主题</span>
          <button class="theme-toggle" type="button" @click="toggleTheme">
            <span>{{ currentThemeLabel }}</span>
            <strong>{{ theme === 'dark' ? '切换浅色' : '切换深色' }}</strong>
          </button>
        </div>
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
      <template v-if="isLoggedIn">
      <section class="left-column">
        <article v-if="canManageDocs" class="panel card upload-card">
          <div class="section-head">
            <div>
              <p class="section-kicker">UPLOAD</p>
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

        <article v-else class="panel card auth-note-card">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">UPLOAD</p>
              <h2>文档上传已关闭</h2>
            </div>
          </div>
          <p class="card-desc">
            当前账号为职工角色，仅可使用企业知识问答，不能上传企业文档。
          </p>
        </article>

        <article class="panel card stats-card">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">RAG STATUS</p>
              <h2>知识库状态</h2>
            </div>
          </div>

          <div class="stats-grid">
            <button class="stat-box stat-button" type="button" @click="openFileDialog">
              <span>已上传文件数</span>
              <strong>{{ knowledgeStats.files }}</strong>
              <small>{{ canManageDocs ? '点击管理文件' : '点击下载文件' }}</small>
            </button>
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
            <article v-for="item in activityFeed" :key="item.id" class="feed-item" :data-level="item.level">
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
        <article class="panel card history-card">
          <div class="section-head compact history-head">
            <div>
              <p class="section-kicker">CHAT MEMORY</p>
              <h2>聊天历史记录</h2>
            </div>
            <span class="state-text">保留 100 条</span>
          </div>

          <div class="history-toolbar">
            <input
              v-model="historyKeyword"
              class="history-search"
              type="text"
              placeholder="搜索提问或回复内容"
              @keyup.enter="loadChatHistory"
            />
            <button class="secondary-btn history-btn" type="button" @click="loadChatHistory">搜索</button>
          </div>

          <p v-if="chatHistoryError" class="feedback danger-text">{{ chatHistoryError }}</p>

          <div class="history-list">
            <div v-if="isLoadingHistory" class="history-empty">聊天历史加载中...</div>
            <div v-else-if="!chatHistory.length" class="history-empty">当前还没有历史问答记录。</div>
            <article v-else v-for="item in chatHistory" :key="item.id" class="history-item">
              <div class="history-topline">
                <div class="history-time">{{ item.formattedTime }}</div>
                <div class="history-chip">问答归档</div>
              </div>

              <div class="history-block question-block">
                <div class="history-mark ask-mark">问</div>
                <div class="history-copy">
                  <div class="history-preview">{{ item.previewQuestion }}</div>
                  <p>{{ item.questionText || item.question }}</p>
                </div>
              </div>

              <div class="history-divider"></div>

              <div class="history-block answer-block">
                <div class="history-mark answer-mark">答</div>
                <div class="history-copy">
                  <div class="history-preview">{{ item.previewAnswer }}</div>
                  <p>{{ item.answerText || item.answer }}</p>
                </div>
              </div>
            </article>
          </div>
        </article>

        <article class="panel card ask-card">
          <div class="section-head">
            <div>
              <p class="section-kicker">Ask</p>
              <h2>向 AI 提问</h2>
            </div>
            <span class="pill">Netty /ws/chat · JSON</span>
          </div>

          <div class="field-grid two-rows" style="display: none;">
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
      </template>
      <section v-else style="padding-left: 10%;">
      
        <h2>暂未登录</h2>
      </section>
    </main>

    <div v-if="isFileDialogOpen" class="dialog-mask" @click.self="closeFileDialog">
      <div class="dialog-panel">
        <div class="section-head compact dialog-head">
          <div>
            <p class="section-kicker">FILES</p>
            <h2>企业文件管理</h2>
          </div>
          <button class="dialog-close" type="button" @click="closeFileDialog">×</button>
        </div>

        <p v-if="fileDialogError" class="feedback danger-text">{{ fileDialogError }}</p>
        <p v-else class="feedback">共 {{ managedFiles.length }} 个文件，可下载到本地或直接删除。</p>

        <div class="file-list">
          <div v-if="isLoadingFiles" class="file-empty">文件列表加载中...</div>
          <div v-else-if="!managedFiles.length" class="file-empty">当前企业还没有已上传文件。</div>
          <article v-else v-for="file in managedFiles" :key="file.storedFilename" class="file-row">
            <div class="file-meta">
              <strong>{{ file.originalFilename || file.storedFilename }}</strong>
              <span>切片 {{ file.segmentsIndexed || 0 }} · {{ file.uploadedAt || '未知时间' }}</span>
            </div>
            <div class="file-actions">
              <button class="secondary-btn" type="button" @click="downloadManagedFile(file)">下载</button>
              <button
                v-if="canManageDocs"
                class="danger-btn"
                type="button"
                :disabled="deletingFileName === file.storedFilename"
                @click="deleteManagedFile(file)"
              >
                {{ deletingFileName === file.storedFilename ? '删除中...' : '删除' }}
              </button>
            </div>
          </article>
        </div>
      </div>
    </div>
  </div>
  </div>
</template>

<style scoped>
:global(:root),
:global(body) {
  color-scheme: dark;
  font-family: 'Sarasa Gothic SC', 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
  line-height: 1.5;
  font-weight: 400;
  font-synthesis: none;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  --bg: #0b0b14;
  --bg-2: #151323;
  --panel: rgba(20, 20, 34, 0.86);
  --panel-lite: rgba(27, 27, 44, 0.92);
  --line: rgba(255, 255, 255, 0.08);
  --line-strong: rgba(255, 176, 108, 0.35);
  --text: #f5f0e8;
  --muted: #b9ad9b;
  --accent: #ffb06c;
  --accent-2: #ff7d66;
  --accent-3: #ffdca8;
  --success: #75d6a0;
  --danger: #ff8f88;
  --warn: #f4cf72;
  --shadow: 0 24px 80px rgba(0, 0, 0, 0.45);
  --shadow-soft: 0 16px 42px rgba(255, 176, 108, 0.14);
  --panel-overlay: rgba(255, 255, 255, 0.04);
  --surface-soft: rgba(255, 255, 255, 0.03);
  --surface-strong: rgba(12, 11, 20, 0.78);
  --input-bg: rgba(8, 8, 15, 0.72);
  --grid-line-a: rgba(255, 255, 255, 0.04);
  --grid-line-b: rgba(255, 255, 255, 0.03);
  --hero-glow: radial-gradient(circle, rgba(255, 176, 108, 0.18), transparent 68%);
  --glow-left: rgba(255, 176, 108, 0.2);
  --glow-right: rgba(255, 125, 102, 0.16);
  --overlay-opacity: 0.24;
  --divider-line: rgba(255, 255, 255, 0.08);
  --chip-border: rgba(255, 176, 108, 0.16);
  --chip-bg: rgba(255, 176, 108, 0.08);
  --ask-mark-bg: linear-gradient(135deg, rgba(255, 176, 108, 0.24), rgba(255, 220, 168, 0.12));
  --answer-mark-bg: linear-gradient(135deg, rgba(255, 125, 102, 0.22), rgba(255, 143, 136, 0.12));
  --answer-mark-text: #ffd6cf;
  --feed-surface: rgba(255, 255, 255, 0.025);
  --feed-success-border: rgba(117, 214, 160, 0.22);
  --feed-danger-border: rgba(255, 143, 136, 0.22);
  --feed-warn-border: rgba(244, 207, 114, 0.22);
  --dialog-mask: rgba(5, 5, 10, 0.62);
  --shell-bg: var(--body-bg);
  --shell-overlay-opacity: var(--overlay-opacity);
  --shell-grid-a: var(--grid-line-a);
  --shell-grid-b: var(--grid-line-b);
  --shell-mask: var(--backdrop-mask);
  --backdrop-mask: radial-gradient(circle at center, #000 40%, transparent 92%);
  --body-bg:
    radial-gradient(circle at 12% 10%, rgba(255, 176, 108, 0.18), transparent 24%),
    radial-gradient(circle at 82% 18%, rgba(255, 125, 102, 0.16), transparent 22%),
    radial-gradient(circle at 55% 80%, rgba(255, 220, 168, 0.08), transparent 26%),
    linear-gradient(135deg, #090910 0%, #11101b 38%, #1a1624 100%);
}

:global(:root[data-theme='light']),
:global(body[data-theme='light']) {
  color-scheme: light;
  --bg: #f7f1e7;
  --bg-2: #efe3d2;
  --panel: rgba(255, 250, 243, 0.92);
  --panel-lite: rgba(255, 247, 238, 0.98);
  --line: rgba(123, 86, 53, 0.16);
  --line-strong: rgba(176, 98, 34, 0.34);
  --text: #2f241b;
  --muted: #5f4b3a;
  --accent: #a85a1f;
  --accent-2: #cf7c34;
  --accent-3: #7e4318;
  --success: #2e8c68;
  --danger: #bf5146;
  --warn: #a57413;
  --shadow: 0 18px 42px rgba(176, 130, 85, 0.12);
  --shadow-soft: 0 18px 38px rgba(223, 181, 132, 0.22);
  --panel-overlay: rgba(255, 255, 255, 0.72);
  --surface-soft: rgba(255, 255, 255, 0.78);
  --surface-strong: rgba(255, 253, 249, 0.98);
  --input-bg: rgba(255, 255, 255, 0.96);
  --grid-line-a: rgba(162, 131, 103, 0.06);
  --grid-line-b: rgba(162, 131, 103, 0.03);
  --hero-glow: radial-gradient(circle, rgba(238, 189, 132, 0.22), transparent 68%);
  --glow-left: rgba(255, 210, 167, 0.38);
  --glow-right: rgba(245, 188, 153, 0.28);
  --overlay-opacity: 0.16;
  --divider-line: rgba(162, 131, 103, 0.14);
  --chip-border: rgba(191, 126, 64, 0.18);
  --chip-bg: rgba(255, 241, 224, 0.88);
  --ask-mark-bg: linear-gradient(135deg, rgba(255, 214, 171, 0.9), rgba(255, 241, 220, 0.7));
  --answer-mark-bg: linear-gradient(135deg, rgba(255, 205, 186, 0.9), rgba(255, 235, 225, 0.76));
  --answer-mark-text: #9b5732;
  --feed-surface: rgba(255, 255, 255, 0.74);
  --feed-success-border: rgba(46, 140, 104, 0.2);
  --feed-danger-border: rgba(191, 81, 70, 0.2);
  --feed-warn-border: rgba(165, 116, 19, 0.2);
  --dialog-mask: rgba(245, 236, 225, 0.68);
  --shell-bg:
    radial-gradient(circle at 16% 12%, rgba(255, 226, 192, 0.92), transparent 24%),
    radial-gradient(circle at 82% 16%, rgba(255, 212, 185, 0.72), transparent 22%),
    radial-gradient(circle at 50% 100%, rgba(248, 224, 194, 0.68), transparent 28%),
    linear-gradient(180deg, #fbf7f1 0%, #f6efe4 52%, #efe4d5 100%);
  --shell-overlay-opacity: 0.12;
  --shell-grid-a: rgba(162, 131, 103, 0.05);
  --shell-grid-b: rgba(162, 131, 103, 0.025);
  --shell-mask: radial-gradient(circle at center, #000 30%, transparent 94%);
  --backdrop-mask: radial-gradient(circle at center, #000 34%, transparent 92%);
  --body-bg:
    radial-gradient(circle at 16% 12%, rgba(255, 226, 192, 0.92), transparent 24%),
    radial-gradient(circle at 82% 16%, rgba(255, 212, 185, 0.72), transparent 22%),
    radial-gradient(circle at 50% 100%, rgba(248, 224, 194, 0.68), transparent 28%),
    linear-gradient(180deg, #fbf7f1 0%, #f6efe4 52%, #efe4d5 100%);
}

* {
  box-sizing: border-box;
}

html,
body,
#app {
  margin: 0;
  min-height: 100%;
}

body {
  min-width: 320px;
  min-height: 100vh;
  color: var(--text);
  background: var(--shell-bg);
  transition: background 0.35s ease, color 0.25s ease;
}

body::before {
  content: none;
}

button,
input,
select,
textarea {
  font: inherit;
}

button {
  border: 0;
}

textarea {
  resize: vertical;
}

#app {
  min-height: 100vh;
}

.site-shell {
  position: relative;
  min-height: 100vh;
  color: var(--text);
  background: var(--shell-bg);
  isolation: isolate;
  transition: background 0.35s ease, color 0.25s ease;
}

.site-bg-layer {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background: var(--shell-bg);
}

.site-bg-layer::before {
  content: '';
  position: absolute;
  inset: 0;
  opacity: var(--shell-overlay-opacity);
  background-image:
    linear-gradient(var(--shell-grid-a) 1px, transparent 1px),
    linear-gradient(90deg, var(--shell-grid-b) 1px, transparent 1px);
  background-size: 32px 32px;
  mask-image: var(--shell-mask);
}

.app-shell {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  padding: 26px;
}

.glow {
  position: fixed;
  width: 300px;
  height: 300px;
  border-radius: 999px;
  filter: blur(90px);
  pointer-events: none;
  opacity: 0.4;
  mix-blend-mode: screen;
}

.glow-left {
  top: -40px;
  left: -20px;
  background: var(--glow-left);
}

.glow-right {
  right: 2%;
  bottom: 2%;
  background: var(--glow-right);
}

.panel {
  position: relative;
  z-index: 1;
  border: 1px solid var(--line);
  border-radius: 30px;
  background: linear-gradient(180deg, var(--panel-overlay), transparent 16%), var(--panel);
  backdrop-filter: blur(18px);
  box-shadow: var(--shadow);
  transition: background 0.28s ease, border-color 0.28s ease, box-shadow 0.28s ease;
}

.panel-lite {
  border: 1px solid var(--line);
  border-radius: 24px;
  background: var(--panel-lite);
  transition: background 0.28s ease, border-color 0.28s ease;
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) 320px;
  gap: 22px;
  padding: 30px;
  overflow: hidden;
}

.hero::after {
  content: '';
  position: absolute;
  inset: auto 0 0 auto;
  width: 240px;
  height: 240px;
  background: var(--hero-glow);
  pointer-events: none;
}

.eyebrow,
.section-kicker,
.panel-kicker {
  margin: 0 0 10px;
  color: var(--accent);
  font-size: 12px;
  letter-spacing: 0.28em;
  text-transform: uppercase;
}

.hero h1,
.section-head h2 {
  margin: 0;
  font-family: 'YouYuan', 'STZhongsong', 'Microsoft YaHei', serif;
}

.hero h1 {
  font-size: 44px;
  line-height: 1.12;
  letter-spacing: 0.04em;
}

.hero-text,
.card-desc,
.feed-item p,
.placeholder-text,
.answer-surface p {
  margin: 0;
  color: var(--muted);
  line-height: 1.85;
}

.hero-text {
  margin-top: 16px;
  max-width: 760px;
  font-size: 15px;
}

.hero-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 22px;
}

.hero-tags span,
.pill,
.state-text {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid color-mix(in srgb, var(--accent) 22%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, var(--surface-soft));
  color: var(--accent-3);
  font-size: 12px;
}

.hero-side {
  padding: 20px;
  align-self: stretch;
}

.theme-switcher {
  display: grid;
  gap: 8px;
  margin-bottom: 18px;
}

.theme-caption {
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.12em;
}

.theme-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  width: 100%;
  min-height: 48px;
  padding: 0 16px;
  border-radius: 18px;
  border: 1px solid var(--line);
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--accent) 12%, transparent), color-mix(in srgb, var(--accent-2) 8%, transparent)),
    var(--surface-soft);
  color: var(--text);
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.theme-toggle span {
  color: var(--accent-3);
  font-size: 13px;
}

.theme-toggle strong {
  font-size: 13px;
  font-weight: 600;
}

.theme-toggle:hover {
  transform: translateY(-1px);
  border-color: var(--line-strong);
  box-shadow: var(--shadow-soft);
}

.hero-model {
  margin: 6px 0 18px;
  font-size: 28px;
  font-weight: 700;
  color: var(--accent);
}

.hero-lines {
  display: grid;
  gap: 14px;
}

.hero-lines span,
.field span,
.stat-box span,
.feed-top span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.hero-lines strong {
  display: block;
  margin-top: 7px;
  color: var(--text);
  font-size: 14px;
  word-break: break-all;
}

.layout-grid {
  display: grid;
  grid-template-columns: 420px minmax(0, 1fr);
  gap: 20px;
  margin-top: 20px;
}

.left-column,
.right-column {
  display: grid;
  gap: 20px;
  align-content: start;
}

.card {
  padding: 22px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 16px;
}

.section-head.compact {
  margin-bottom: 14px;
}

.section-head h2 {
  font-size: 28px;
  line-height: 1.2;
}

.card-desc {
  margin-bottom: 18px;
  font-size: 14px;
}

.upload-zone {
  position: relative;
  display: grid;
  gap: 10px;
  padding: 26px 22px;
  border-radius: 24px;
  border: 1px dashed color-mix(in srgb, var(--accent) 35%, transparent);
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--accent) 10%, transparent), transparent 80%),
    var(--surface-soft);
  cursor: pointer;
}

.upload-zone input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.upload-zone strong {
  font-size: 18px;
  color: var(--accent-3);
}

.upload-zone span {
  color: var(--muted);
  font-size: 13px;
}

.upload-actions,
.ask-actions {
  display: flex;
  gap: 12px;
  margin-top: 18px;
}

.primary-btn,
.secondary-btn,
.danger-btn {
  min-height: 48px;
  padding: 0 18px;
  border-radius: 16px;
  color: var(--text);
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease, opacity 0.18s ease;
}

.primary-btn {
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  box-shadow: 0 12px 28px rgba(255, 125, 102, 0.22);
}

.secondary-btn {
  background: var(--surface-soft);
  border: 1px solid var(--line);
}

.danger-btn {
  background: rgba(255, 143, 136, 0.12);
  border: 1px solid rgba(255, 143, 136, 0.28);
}

.primary-btn:hover,
.secondary-btn:hover,
.danger-btn:hover {
  transform: translateY(-1px);
}

.primary-btn:disabled,
.secondary-btn:disabled,
.danger-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
}

.feedback {
  margin: 14px 0 0;
  font-size: 13px;
}

.success-text {
  color: var(--success);
}

.danger-text {
  color: var(--danger);
}

.stats-grid {
  display: grid;
  gap: 12px;
}

.stat-box {
  padding: 18px;
  border-radius: 22px;
  border: 1px solid var(--line);
  background: var(--surface-soft);
}

.stat-button {
  width: 100%;
  text-align: left;
  color: inherit;
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.stat-button:hover {
  transform: translateY(-2px);
  border-color: rgba(255, 176, 108, 0.32);
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.18);
}

.stat-button small {
  display: inline-block;
  margin-top: 10px;
  color: var(--accent-3);
  font-size: 12px;
}

.stat-box strong {
  display: block;
  margin-top: 10px;
  font-size: 34px;
  color: var(--accent-3);
}

.accent-box {
  background: linear-gradient(135deg, rgba(255, 176, 108, 0.1), rgba(255, 125, 102, 0.08));
  border-color: rgba(255, 176, 108, 0.18);
}

.feed-list {
  display: grid;
  gap: 12px;
  max-height: 400px;
  overflow: auto;
}

.feed-item {
  padding: 16px;
  border-radius: 20px;
  border: 1px solid var(--line);
  background: var(--feed-surface);
}

.feed-item[data-level='success'] {
  border-color: var(--feed-success-border);
}

.feed-item[data-level='danger'] {
  border-color: var(--feed-danger-border);
}

.feed-item[data-level='warn'] {
  border-color: var(--feed-warn-border);
}

.feed-top {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.feed-top strong {
  font-size: 14px;
}

.history-card {
  height: 430px;
  display: grid;
  grid-template-rows: auto auto 1fr;
  overflow: hidden;
}

.history-head {
  align-items: center;
}

.history-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  min-width: 0;
}

.history-search {
  flex: 1;
  min-width: 0;
  min-height: 46px;
  padding: 0 16px;
  border-radius: 16px;
  border: 1px solid var(--line);
  background: var(--input-bg);
  color: var(--text);
  outline: none;
}

.history-search:focus {
  border-color: var(--line-strong);
  box-shadow: 0 0 0 1px rgba(255, 176, 108, 0.14);
}

.history-btn {
  min-width: 90px;
  flex-shrink: 0;
}

.history-list {
  display: grid;
  gap: 14px;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 6px;
}

.history-item {
  position: relative;
  min-width: 0;
  padding: 18px;
  border-radius: 24px;
  border: 1px solid var(--line);
  background:
    radial-gradient(circle at top right, color-mix(in srgb, var(--accent) 10%, transparent), transparent 28%),
    linear-gradient(180deg, color-mix(in srgb, var(--accent) 4%, transparent), transparent 48%),
    var(--surface-soft);
}

.history-topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.history-time,
.history-empty {
  color: var(--muted);
  font-size: 12px;
}

.history-time {
  letter-spacing: 0.08em;
}

.history-chip {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--chip-border);
  background: var(--chip-bg);
  color: var(--accent-3);
  font-size: 11px;
  letter-spacing: 0.14em;
}

.history-block {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  min-width: 0;
}

.history-mark {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 16px;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.ask-mark {
  background: var(--ask-mark-bg);
  color: var(--accent-3);
}

.answer-mark {
  background: var(--answer-mark-bg);
  color: var(--answer-mark-text);
}

.history-copy {
  min-width: 0;
}

.history-preview {
  margin-bottom: 8px;
  color: var(--accent-3);
  font-size: 13px;
  line-height: 1.5;
}

.history-copy p {
  margin: 0;
  color: var(--muted);
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  line-height: 1.8;
}

.history-divider {
  height: 1px;
  margin: 14px 0;
  background: linear-gradient(90deg, transparent, var(--divider-line), transparent);
}

.field-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 220px;
  gap: 14px;
}

.field {
  display: grid;
  gap: 8px;
}

.field input,
.field select,
.field textarea {
  width: 100%;
  border-radius: 16px;
  border: 1px solid var(--line);
  background: var(--input-bg);
  color: var(--text);
  outline: none;
  padding: 14px 16px;
}

.field input:focus,
.field select:focus,
.field textarea:focus {
  border-color: var(--line-strong);
  box-shadow: 0 0 0 1px rgba(255, 176, 108, 0.14);
}

.question-field {
  margin-top: 16px;
}

.answer-card {
  min-height: 420px;
}

.answer-surface {
  min-height: 300px;
  padding: 22px;
  border-radius: 24px;
  border: 1px solid var(--line);
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--accent) 6%, transparent), transparent 36%),
    var(--surface-strong);
  white-space: pre-wrap;
}

.answer-surface.streaming {
  box-shadow: inset 0 0 0 1px rgba(255, 176, 108, 0.14);
}

.answer-surface p {
  color: var(--text);
  font-size: 15px;
}

.placeholder-text {
  color: var(--muted) !important;
}

.dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 20px;
  background: var(--dialog-mask);
  backdrop-filter: blur(10px);
}

.dialog-panel {
  width: min(860px, 100%);
  max-height: 80vh;
  overflow: auto;
  padding: 22px;
  border-radius: 28px;
  border: 1px solid var(--line);
  background: linear-gradient(180deg, var(--panel-overlay), transparent 20%), var(--panel-lite);
  box-shadow: var(--shadow);
}

.dialog-head {
  align-items: center;
}

.dialog-close {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.04);
  color: var(--text);
  cursor: pointer;
}

.file-list {
  display: grid;
  gap: 12px;
  margin-top: 18px;
}

.file-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 16px 18px;
  border-radius: 20px;
  border: 1px solid var(--line);
  background: var(--surface-soft);
}

.file-meta {
  display: grid;
  gap: 6px;
}

.file-meta strong {
  font-size: 16px;
  word-break: break-all;
}

.file-meta span,
.file-empty {
  color: var(--muted);
  font-size: 13px;
}

.file-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

@media (max-width: 780px) {
  .file-row {
    flex-direction: column;
    align-items: flex-start;
  }

  .file-actions {
    width: 100%;
  }

  .danger-btn {
    width: 100%;
  }
}

@media (max-width: 1180px) {
  .hero,
  .layout-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 780px) {
  .app-shell {
    padding: 14px;
  }

  .hero,
  .card {
    padding: 18px;
  }

  .section-head,
  .feed-top,
  .ask-actions,
  .history-toolbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .field-grid {
    grid-template-columns: 1fr;
  }

  .primary-btn,
  .secondary-btn {
    width: 100%;
  }

  .hero h1 {
    font-size: 32px;
  }

  .section-head h2 {
    font-size: 24px;
  }
}
</style>