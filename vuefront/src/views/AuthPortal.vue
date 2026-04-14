<script setup>
import TopNav from '../components/TopNav.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { applyEnterprise, listEnterprises, login, register } from '../api/auth'
import { clearProfile, clearToken, getToken, saveProfile, saveToken } from '../utils/token'

const props = defineProps({
  mode: {
    type: String,
    default: 'login',
  },
})

const router = useRouter()
const submitting = ref(false)
const loadingEnterprises = ref(false)
const enterpriseOptions = ref([])
const submitMessage = ref('')
const submitError = ref('')
const savedToken = ref(getToken())
const backendBase = 'http://127.0.0.1:8090'

const modeMap = {
  login: {
    title: '注册账号',
    kicker: 'REGISTER',
    subtitle: '加入已有企业，作为管理员或职工提交注册申请。',
  },
  register: {
    title: '账号登录',
    kicker: 'LOGIN',
    subtitle: '通过已审核账号登录系统，获取平台访问 Token。',
  },
  add: {
    title: '注册公司',
    kicker: 'ENTERPRISE',
    subtitle: '创建企业档案并自动生成企业所有者账号。',
  },
}

const pageInfo = computed(() => modeMap[props.mode] || modeMap.register)

const loginForm = reactive({
  username: '',
  password: '',
})

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nick: '',
  enterpriseId: '',
  role: 2,
})

const enterpriseForm = reactive({
  enterpriseName: '',
  description: '',
  logo: '',
  username: '',
  password: '',
  confirmPassword: '',
  nick: '',
})

const canSubmitLogin = computed(() => loginForm.username.trim() && loginForm.password.trim())
const canSubmitRegister = computed(() => {
  return registerForm.username.trim() && registerForm.password.trim() && registerForm.confirmPassword.trim() && registerForm.nick.trim() && registerForm.enterpriseId !== ''
})
const canSubmitEnterprise = computed(() => {
  return enterpriseForm.enterpriseName.trim() && enterpriseForm.username.trim() && enterpriseForm.password.trim() && enterpriseForm.confirmPassword.trim()
})

function resetFeedback() {
  submitMessage.value = ''
  submitError.value = ''
}

function mapRoleLabel(role) {
  if (Number(role) === 0) return '所有者'
  if (Number(role) === 1) return '管理员'
  return '职工'
}

function decodeTokenPayload(token) {
  try {
    const parts = token.split('.')
    if (parts.length < 2) return null
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(decodeURIComponent(escape(window.atob(payload))))
  } catch {
    return null
  }
}

function readRoleFromToken(token) {
  return decodeTokenPayload(token)?.role ?? 2
}

function readEnterpriseIdFromToken(token) {
  return decodeTokenPayload(token)?.enterprise_id
}

watch(
  () => props.mode,
  (mode) => {
    resetFeedback()
    if (mode === 'login' && !enterpriseOptions.value.length) {
      loadEnterprises()
    }
  }
)

async function loadEnterprises() {
  loadingEnterprises.value = true
  try {
    const { data } = await listEnterprises()
    enterpriseOptions.value = Array.isArray(data?.data) ? data.data : []
  } catch (error) {
    submitError.value = error?.response?.data?.message || '企业列表加载失败，请稍后再试。'
  } finally {
    loadingEnterprises.value = false
  }
}

onMounted(() => {
  if (props.mode === 'login') {
    loadEnterprises()
  }
})

async function handleRegister() {
  resetFeedback()
  if (registerForm.password !== registerForm.confirmPassword) {
    submitError.value = '密码和确认密码不一致'
    return
  }
  if (registerForm.enterpriseId === '') {
    submitError.value = '请选择公司'
    return
  }

  submitting.value = true
  try {
    const { data } = await register({
      username: registerForm.username,
      password: registerForm.password,
      confirm_password: registerForm.confirmPassword,
      nick: registerForm.nick,
      enterprise_id: Number(registerForm.enterpriseId),
      role: Number(registerForm.role),
    })
    submitMessage.value = data?.message || '注册申请已提交，请等待审核。'
  } catch (error) {
    submitError.value = error?.response?.data?.message || '注册失败，请稍后再试。'
  } finally {
    submitting.value = false
  }
}

async function handleLogin() {
  resetFeedback()
  submitting.value = true
  try {
    const { data } = await login({
      username: loginForm.username,
      password: loginForm.password,
    })
    const token = data?.data?.token || data?.token || ''
    if (!token) {
      throw new Error('登录成功但未收到 token')
    }
    saveToken(token)
    saveProfile({
      username: loginForm.username,
      nick: loginForm.username,
      enterpriseId: readEnterpriseIdFromToken(token),
      role: Number(readRoleFromToken(token)),
      roleLabel: mapRoleLabel(readRoleFromToken(token)),
    })
    savedToken.value = token
    submitMessage.value = '登录成功，Token 已保存。即将进入个人页。'
    setTimeout(() => router.push('/mine'), 600)
  } catch (error) {
    submitError.value = error?.response?.data?.message || error?.message || '登录失败，请检查用户名和密码。'
  } finally {
    submitting.value = false
  }
}

async function handleApplyEnterprise() {
  resetFeedback()
  if (enterpriseForm.password !== enterpriseForm.confirmPassword) {
    submitError.value = '密码和确认密码不一致'
    return
  }

  submitting.value = true
  try {
    const { data } = await applyEnterprise({
      username: enterpriseForm.username,
      password: enterpriseForm.password,
      confirm_password: enterpriseForm.confirmPassword,
      nick: enterpriseForm.nick,
      enterprise_id: 0,
      enterprise_name: enterpriseForm.enterpriseName,
      description: enterpriseForm.description,
      logo: enterpriseForm.logo,
    })
    const token = data?.data?.token || data?.token || ''
    if (token) {
      saveToken(token)
      saveProfile({
        username: data?.data?.username || enterpriseForm.username,
        nick: enterpriseForm.nick || enterpriseForm.username,
        enterpriseId: data?.data?.enterprise_id,
        role: 0,
        roleLabel: '所有者',
      })
      savedToken.value = token
    }
    submitMessage.value = '企业创建成功，所有者账号已自动登录。即将进入个人页。'
    setTimeout(() => router.push('/mine'), 600)
  } catch (error) {
    submitError.value = error?.response?.data?.message || '企业申请失败，请检查输入信息。'
  } finally {
    submitting.value = false
  }
}

function logout() {
  clearToken()
  clearProfile()
  savedToken.value = ''
  submitMessage.value = '已退出登录，本地 Token 已清除。'
  submitError.value = ''
}
</script>

<template>
  <div class="site-shell auth-shell-page">
    <div class="site-aurora site-aurora-left"></div>
    <div class="site-aurora site-aurora-right"></div>
    <TopNav />

    <section class="auth-page">
      <div class="auth-side auth-glass">
        <p class="auth-kicker">{{ pageInfo.kicker }}</p>
        <h1>{{ pageInfo.title }}</h1>
        <p class="auth-desc">{{ pageInfo.subtitle }}</p>

        <div class="auth-meta">
          <article>
            <span>后端地址</span>
            <strong>{{ backendBase }}</strong>
          </article>
          <article>
            <span>当前会话</span>
            <strong>{{ savedToken ? '已保存 Token' : '未登录' }}</strong>
          </article>
        </div>

        <button class="ghost-btn" :disabled="!savedToken" @click="logout">清除本地 Token</button>
      </div>

      <div class="auth-main auth-glass">
        <form v-if="mode === 'register'" class="auth-form" @submit.prevent="handleLogin">
          <label class="field">
            <span>用户名</span>
            <input v-model="loginForm.username" placeholder="请输入用户名" />
          </label>
          <label class="field">
            <span>密码</span>
            <input v-model="loginForm.password" type="password" placeholder="请输入密码" />
          </label>
          <button class="primary-btn" :disabled="submitting || !canSubmitLogin">
            {{ submitting ? '登录中...' : '立即登录' }}
          </button>
          <div class="auth-actions-row full-row">
            <RouterLink to="/register" class="ghost-btn inline-link-btn full-width-link">没有账户？注册账户</RouterLink>
          </div>
        </form>

        <form v-else-if="mode === 'login'" class="auth-form two-column" @submit.prevent="handleRegister">
          <label class="field">
            <span>用户名</span>
            <input v-model="registerForm.username" placeholder="如 zhangsan" />
          </label>
          <label class="field">
            <span>昵称</span>
            <input v-model="registerForm.nick" placeholder="请输入昵称" />
          </label>
          <label class="field full-row">
            <span>所属公司</span>
            <select v-model="registerForm.enterpriseId" :disabled="loadingEnterprises">
              <option value="">{{ loadingEnterprises ? '加载公司列表中...' : '请选择公司' }}</option>
              <option v-for="enterprise in enterpriseOptions" :key="enterprise.id" :value="enterprise.id">
                {{ enterprise.name }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>密码</span>
            <input v-model="registerForm.password" type="password" placeholder="请输入密码" />
          </label>
          <label class="field">
            <span>确认密码</span>
            <input v-model="registerForm.confirmPassword" type="password" placeholder="请再次输入密码" />
          </label>
          <label class="field">
            <span>申请角色</span>
            <select v-model="registerForm.role">
              <option :value="1">管理员</option>
              <option :value="2">职工</option>
            </select>
          </label>
          <button class="primary-btn full-row" :disabled="submitting || !canSubmitRegister">
            {{ submitting ? '提交中...' : '提交注册申请' }}
          </button>
          <div class="auth-actions-row full-row">
            <RouterLink to="/add" class="ghost-btn inline-link-btn">注册公司</RouterLink>
          </div>
        </form>

        <form v-else class="auth-form two-column" @submit.prevent="handleApplyEnterprise">
          <label class="field">
            <span>企业名称</span>
            <input v-model="enterpriseForm.enterpriseName" placeholder="请输入企业名称" />
          </label>
          <label class="field full-row">
            <span>企业描述</span>
            <textarea v-model="enterpriseForm.description" rows="4" placeholder="简要描述企业定位、业务场景与知识库目标"></textarea>
          </label>
          <label class="field full-row">
            <span>企业 Logo 地址</span>
            <input v-model="enterpriseForm.logo" placeholder="可选，填写企业 Logo URL" />
          </label>
          <label class="field">
            <span>所有者用户名</span>
            <input v-model="enterpriseForm.username" placeholder="请输入所有者登录账号" />
          </label>
          <label class="field">
            <span>所有者昵称</span>
            <input v-model="enterpriseForm.nick" placeholder="请输入所有者昵称" />
          </label>
          <label class="field">
            <span>登录密码</span>
            <input v-model="enterpriseForm.password" type="password" placeholder="请输入密码" />
          </label>
          <label class="field">
            <span>确认密码</span>
            <input v-model="enterpriseForm.confirmPassword" type="password" placeholder="请再次输入密码" />
          </label>
          <button class="primary-btn full-row" :disabled="submitting || !canSubmitEnterprise">
            {{ submitting ? '提交中...' : '创建公司并自动登录' }}
          </button>
        </form>

        <div class="feedback-box">
          <p v-if="submitMessage" class="feedback success">{{ submitMessage }}</p>
          <p v-if="submitError" class="feedback error">{{ submitError }}</p>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.auth-shell-page {
  min-height: 100vh;
  color: var(--text);
  background: var(--shell-bg, var(--body-bg, transparent));
  --meta-card-bg: var(--surface-soft);
  --meta-card-border: var(--line);
  --meta-card-text: var(--text);
  --meta-card-muted: var(--muted);
  --field-bg: rgba(12, 14, 24, 0.78);
  --field-border: rgba(255, 255, 255, 0.08);
  --field-text: var(--text);
  --field-placeholder: rgba(185, 173, 155, 0.72);
  --field-focus-border: var(--line-strong);
  --field-focus-ring: rgba(255, 176, 108, 0.14);
  --field-select-arrow: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='%23b9ad9b' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  --btn-primary-bg: linear-gradient(135deg, var(--accent), var(--accent-2));
  --btn-primary-text: #fff8f1;
  --btn-primary-shadow: 0 14px 30px rgba(255, 125, 102, 0.24);
  --btn-ghost-bg: rgba(255, 255, 255, 0.04);
  --btn-ghost-border: rgba(255, 255, 255, 0.08);
  --btn-ghost-text: var(--text);
  --btn-ghost-hover-bg: rgba(255, 255, 255, 0.08);
  --btn-pass-bg: rgba(117, 214, 160, 0.12);
  --btn-pass-border: rgba(117, 214, 160, 0.28);
  --btn-pass-text: #d7ffe5;
  --btn-reject-bg: rgba(255, 143, 136, 0.12);
  --btn-reject-border: rgba(255, 143, 136, 0.28);
  --btn-reject-text: #ffd9d4;
}

.auth-shell-page::before {
  content: '';
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  opacity: var(--shell-overlay-opacity, var(--overlay-opacity, 0));
  background-image:
    linear-gradient(var(--shell-grid-a, var(--grid-line-a, transparent)) 1px, transparent 1px),
    linear-gradient(90deg, var(--shell-grid-b, var(--grid-line-b, transparent)) 1px, transparent 1px);
  background-size: 32px 32px;
  mask-image: var(--shell-mask, var(--backdrop-mask, none));
}

.auth-shell-page:has([data-theme='light']),
.site-shell[data-theme='light'].auth-shell-page,
:global(body[data-theme='light']) .auth-shell-page {
  --meta-card-bg: rgba(255, 251, 246, 0.94);
  --meta-card-border: rgba(183, 126, 78, 0.18);
  --meta-card-text: #5d3820;
  --meta-card-muted: #9c7a60;
  --field-bg: rgba(255, 251, 246, 0.96);
  --field-border: rgba(183, 126, 78, 0.18);
  --field-text: #5d3820;
  --field-placeholder: rgba(156, 122, 96, 0.82);
  --field-focus-border: rgba(196, 132, 72, 0.42);
  --field-focus-ring: rgba(216, 138, 73, 0.16);
  --field-select-arrow: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='%237a461d' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  --btn-primary-bg: linear-gradient(135deg, #b5652a, #d88a49);
  --btn-primary-text: #fffdf8;
  --btn-primary-shadow: 0 12px 26px rgba(196, 132, 72, 0.22);
  --btn-ghost-bg: rgba(255, 248, 240, 0.92);
  --btn-ghost-border: rgba(183, 126, 78, 0.24);
  --btn-ghost-text: #7a461d;
  --btn-ghost-hover-bg: rgba(255, 239, 223, 0.98);
  --btn-pass-bg: rgba(231, 247, 238, 0.96);
  --btn-pass-border: rgba(76, 166, 116, 0.28);
  --btn-pass-text: #2f7e57;
  --btn-reject-bg: rgba(255, 239, 236, 0.96);
  --btn-reject-border: rgba(199, 96, 80, 0.26);
  --btn-reject-text: #b44c3f;
}

.primary-btn,
.ghost-btn,
.audit-icon-btn,
.auth-page {
  position: relative;
  z-index: 1;
}

.primary-btn,
.ghost-btn,
.audit-icon-btn {
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease, border-color 0.18s ease, color 0.18s ease, opacity 0.18s ease;
}

.primary-btn {
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  box-shadow: var(--btn-primary-shadow);
}

.ghost-btn {
  background: var(--btn-ghost-bg);
  border: 1px solid var(--btn-ghost-border);
  color: var(--btn-ghost-text);
}

.ghost-btn:hover,
.inline-link-btn:hover {
  background: var(--btn-ghost-hover-bg);
}

.audit-pass {
  background: var(--btn-pass-bg);
  border: 1px solid var(--btn-pass-border);
  color: var(--btn-pass-text);
}

.audit-reject {
  background: var(--btn-reject-bg);
  border: 1px solid var(--btn-reject-border);
  color: var(--btn-reject-text);
}

.auth-actions-row {
  display: flex;
  justify-content: flex-start;
  margin-top: 12px;
}

.auth-meta article {
  border: 1px solid var(--meta-card-border);
  background: var(--meta-card-bg);
  color: var(--meta-card-text);
}

.auth-meta article span {
  color: var(--meta-card-muted);
}

.auth-meta article strong {
  color: var(--meta-card-text);
}

.field {
  display: grid;
  gap: 10px;
}

.field span {
  color: var(--muted);
}

.field input,
.field select,
.field textarea {
  width: 100%;
  min-height: 58px;
  padding: 0 20px;
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(12, 14, 24, 0.78);
  color: var(--text);
  outline: none;
  appearance: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  box-shadow: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, background 0.18s ease, color 0.18s ease;
}

.field textarea {
  min-height: 120px;
  padding: 16px 20px;
  resize: vertical;
}

.field select {
  padding-right: 56px;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='%23b9ad9b' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: calc(100% - 24px) center;
  background-size: 14px 14px;
}

.field input::placeholder,
.field textarea::placeholder {
  color: rgba(185, 173, 155, 0.72);
}

.field input:focus,
.field select:focus,
.field textarea:focus {
  border-color: var(--line-strong);
  box-shadow: 0 0 0 3px rgba(255, 176, 108, 0.14);
}

:global(body[data-theme='light']) .auth-shell-page .field span {
  color: #e2bf86;
}

:global(body[data-theme='light']) .auth-shell-page .field input,
:global(body[data-theme='light']) .auth-shell-page .field select,
:global(body[data-theme='light']) .auth-shell-page .field textarea {
  background: rgba(255, 251, 246, 0.98) !important;
  background-color: rgba(255, 251, 246, 0.98) !important;
  border: 1px solid rgba(183, 126, 78, 0.18) !important;
  color: #5d3820 !important;
  caret-color: #7a461d;
  -webkit-text-fill-color: #5d3820 !important;
  box-shadow: 0 0 0 1000px rgba(255, 251, 246, 0.98) inset !important;
}

:global(body[data-theme='light']) .auth-shell-page .field input:-webkit-autofill,
:global(body[data-theme='light']) .auth-shell-page .field textarea:-webkit-autofill,
:global(body[data-theme='light']) .auth-shell-page .field select:-webkit-autofill {
  -webkit-text-fill-color: #5d3820 !important;
  -webkit-box-shadow: 0 0 0 1000px rgba(255, 251, 246, 0.98) inset !important;
  transition: background-color 9999s ease-out 0s;
}

:global(body[data-theme='light']) .auth-shell-page .field input::placeholder,
:global(body[data-theme='light']) .auth-shell-page .field textarea::placeholder {
  color: rgba(156, 122, 96, 0.82) !important;
}

:global(body[data-theme='light']) .auth-shell-page .field select {
  background: rgba(255, 251, 246, 0.98) !important;
  background-color: rgba(255, 251, 246, 0.98) !important;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='%237a461d' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E") !important;
  background-repeat: no-repeat !important;
  background-position: calc(100% - 24px) center !important;
  background-size: 14px 14px !important;
}

:global(body[data-theme='light']) .auth-shell-page .field input:focus,
:global(body[data-theme='light']) .auth-shell-page .field select:focus,
:global(body[data-theme='light']) .auth-shell-page .field textarea:focus {
  border-color: rgba(196, 132, 72, 0.42) !important;
  box-shadow: 0 0 0 3px rgba(216, 138, 73, 0.16) !important, 0 0 0 1000px rgba(255, 251, 246, 0.98) inset !important;
}

.inline-link-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
}

.full-width-link {
  width: 100%;
}
</style>
