<script setup>
import TopNav from '../components/TopNav.vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { auditUser, getPendingAuditUsers } from '../api/auth'
import { clearPendingAuditCount, clearProfile, clearToken, getProfile, getToken, savePendingAuditCount } from '../utils/token'

const router = useRouter()
const token = computed(() => getToken())
const profile = computed(() => getProfile())
const displayName = computed(() => profile.value.nick || profile.value.username || '未命名账户')
const pendingUsers = ref([])
const loadingPending = ref(false)
const actionLoadingUserId = ref(0)
const auditMessage = ref('')
const auditError = ref('')

const notifications = computed(() => {
  if (pendingUsers.value.length > 0) {
    return [
      {
        title: '待审批账户申请',
        text: `当前有 ${pendingUsers.value.length} 条账户申请等待处理。`,
        time: '刚刚',
        level: 'warn',
      },
    ]
  }

  return [
    {
      title: '我的消息',
      text: '当前无消息',
      time: '实时',
      level: 'info',
    },
  ]
})

const canAudit = computed(() => {
  return profile.value.roleLabel === '所有者' || profile.value.roleLabel === '管理员'
})

function mapRoleLabel(role) {
  if (Number(role) === 0) return '所有者'
  if (Number(role) === 1) return '管理员'
  return '职工'
}

function mapLoginStatusLabel(status) {
  if (Number(status) === 1) return '审核通过'
  if (Number(status) === 0) return '审核未通过'
  return '待审核'
}

function syncPendingCount(list) {
  const count = Array.isArray(list) ? list.length : 0
  savePendingAuditCount(count)
}

async function loadPendingUsers() {
  if (!token.value || !canAudit.value) {
    pendingUsers.value = []
    clearPendingAuditCount()
    return
  }

  loadingPending.value = true
  auditError.value = ''
  try {
    const { data } = await getPendingAuditUsers()
    pendingUsers.value = Array.isArray(data?.data) ? data.data : []
    syncPendingCount(pendingUsers.value)
  } catch (error) {
    pendingUsers.value = []
    clearPendingAuditCount()
    auditError.value = error?.response?.data?.message || '加载待审批账户失败。'
  } finally {
    loadingPending.value = false
  }
}

async function handleAudit(userId, loginStatus) {
  actionLoadingUserId.value = userId
  auditMessage.value = ''
  auditError.value = ''
  try {
    const { data } = await auditUser({
      user_id: userId,
      login_status: loginStatus,
    })
    auditMessage.value = data?.data || data?.message || '审批完成'
    await loadPendingUsers()
  } catch (error) {
    auditError.value = error?.response?.data?.message || '审批失败，请稍后再试。'
  } finally {
    actionLoadingUserId.value = 0
  }
}

function logout() {
  clearToken()
  clearProfile()
  clearPendingAuditCount()
  router.push('/login')
}

onMounted(() => {
  loadPendingUsers()
})
</script>

<template>
  <div class="site-shell account-shell-page">
    <div class="site-aurora site-aurora-left"></div>
    <div class="site-aurora site-aurora-right"></div>
    <TopNav />

    <section class="account-page">
      <aside class="panel account-side">
        <p class="auth-kicker">ACCOUNT</p>
        <h1>个人页</h1>
        <p class="auth-desc">查看当前登录账户、审批本企业账户申请，并接收待处理通知。</p>

        <div class="account-meta">
          <article class="info-card">
            <span>账户昵称</span>
            <strong>{{ displayName }}</strong>
          </article>
          <article class="info-card">
            <span>登录状态</span>
            <strong>{{ token ? '已登录' : '未登录' }}</strong>
          </article>
        </div>

        <button class="ghost-btn" :disabled="!token" @click="logout">退出登录</button>
      </aside>

      <main class="panel account-main">
        <section class="account-block notice-strip-block">
          <div class="account-block-head notice-strip-head">
            <div>
              <p class="auth-kicker">NOTICE</p>
              <h2>消息通知</h2>
            </div>
          </div>
          <div class="notice-strip-list">
            <article v-for="item in notifications" :key="item.title" class="notice-strip-item">
              <div class="notice-strip-main">
                <strong>{{ item.title }}</strong>
                <p>{{ item.text }}</p>
              </div>
              <span class="notice-strip-time">{{ item.time }}</span>
            </article>
          </div>
        </section>

        <section class="account-block">
          <div class="account-block-head">
            <p class="auth-kicker">PROFILE</p>
            <h2>账户信息</h2>
          </div>
          <div class="account-grid">
            <article class="info-card">
              <span>用户名</span>
              <strong>{{ profile.username || '暂无' }}</strong>
            </article>
            <article class="info-card">
              <span>昵称</span>
              <strong>{{ profile.nick || '暂无' }}</strong>
            </article>
            <article class="info-card">
              <span>角色</span>
              <strong>{{ profile.roleLabel || '暂无' }}</strong>
            </article>
            <article class="info-card">
              <span>企业 ID</span>
              <strong>{{ profile.enterpriseId ?? '暂无' }}</strong>
            </article>
          </div>
        </section>

        <section class="account-block">
          <div class="account-block-head">
            <p class="auth-kicker">AUDIT</p>
            <h2>账户申请审批</h2>
          </div>

          <div v-if="auditMessage" class="feedback success">{{ auditMessage }}</div>
          <div v-if="auditError" class="feedback error">{{ auditError }}</div>

          <div v-if="!canAudit" class="feedback">当前角色无审批权限。职工注册后需管理员或所有者审核；管理员注册需所有者审核。</div>
          <div v-else-if="loadingPending" class="feedback">正在加载待审批账户...</div>
          <div v-else-if="!pendingUsers.length" class="feedback">当前暂无待审批账户。</div>
          <div v-else class="audit-list">
            <article v-for="user in pendingUsers" :key="user.id" class="audit-item">
              <div class="audit-item-info">
                <strong>{{ user.nick || user.username }}</strong>
                <p>用户名：{{ user.username }}</p>
                <p>角色：{{ mapRoleLabel(user.role) }}</p>
                <p>审批状态：{{ mapLoginStatusLabel(user.login_status) }}</p>
              </div>
              <div class="audit-actions">
                <button class="audit-icon-btn audit-pass" :disabled="actionLoadingUserId === user.id" @click="handleAudit(user.id, 1)" aria-label="通过申请" title="通过申请">
                  <span>{{ actionLoadingUserId === user.id ? '…' : '✓' }}</span>
                </button>
                <button class="audit-icon-btn audit-reject" :disabled="actionLoadingUserId === user.id" @click="handleAudit(user.id, 0)" aria-label="驳回申请" title="驳回申请">
                  <span>{{ actionLoadingUserId === user.id ? '…' : '✕' }}</span>
                </button>
              </div>
            </article>
          </div>
        </section>
      </main>
    </section>
  </div>
</template>

<style scoped>
.account-shell-page {
  color: var(--text);
  min-height: 100vh;
  background: var(--shell-bg, var(--body-bg, transparent));
}

.account-shell-page::before {
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

.account-page {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 22px;
  padding: 22px 26px 36px;
}

.account-side,
.account-main {
  padding: 24px;
}

.account-side {
  align-self: start;
}

.auth-kicker {
  margin: 0 0 12px;
  color: var(--accent);
  font-size: 12px;
  letter-spacing: 0.28em;
  text-transform: uppercase;
}

.account-side h1,
.account-block h2 {
  margin: 0;
  font-family: 'YouYuan', 'STZhongsong', 'Microsoft YaHei', serif;
  color: var(--text);
}

.account-side h1 {
  font-size: 42px;
  line-height: 1.08;
  margin-bottom: 16px;
}

.auth-desc,
.notice-strip-main p,
.audit-item-info p,
.feedback,
.info-card span,
.notice-strip-time {
  color: var(--muted);
}

.auth-desc,
.notice-strip-main p,
.audit-item-info p,
.feedback {
  line-height: 1.8;
}

.auth-desc {
  margin: 0 0 20px;
  font-size: 15px;
}

.account-meta,
.audit-list {
  display: grid;
  gap: 14px;
}

.account-meta {
  margin-bottom: 18px;
}

.info-card,
.notice-strip-item,
.audit-item {
  border: 1px solid var(--line);
  border-radius: 22px;
  background: var(--surface-soft);
}

.info-card {
  padding: 18px 18px;
}

.info-card span,
.notice-strip-time {
  display: block;
  font-size: 13px;
}

.info-card strong,
.notice-strip-main strong,
.audit-item-info strong {
  display: block;
  margin-top: 8px;
  color: var(--text);
}

.notice-strip-block,
.account-block + .account-block {
  margin-top: 22px;
}

.notice-strip-block:first-child {
  margin-top: 0;
}

.notice-strip-item,
.audit-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 18px;
}

.notice-strip-main p {
  margin: 8px 0 0;
}

.account-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.audit-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-shrink: 0;
}

.ghost-btn,
.audit-icon-btn {
  min-height: 48px;
  border-radius: 16px;
  border: 1px solid transparent;
  font: inherit;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease, border-color 0.18s ease, color 0.18s ease, opacity 0.18s ease;
}

.ghost-btn {
  width: 100%;
  background: var(--surface-soft);
  border-color: var(--line);
  color: var(--text);
  font-weight: 600;
}

.ghost-btn:hover,
.audit-icon-btn:hover {
  transform: translateY(-1px);
}

.audit-icon-btn {
  width: 48px;
  display: inline-grid;
  place-items: center;
  font-size: 20px;
}

.audit-pass {
  background: color-mix(in srgb, var(--success) 16%, var(--surface-soft));
  border-color: color-mix(in srgb, var(--success) 34%, transparent);
  color: var(--success);
}

.audit-reject {
  background: color-mix(in srgb, var(--danger) 14%, var(--surface-soft));
  border-color: color-mix(in srgb, var(--danger) 32%, transparent);
  color: var(--danger);
}

.audit-icon-btn:disabled,
.ghost-btn:disabled {
  opacity: 0.48;
  cursor: not-allowed;
  transform: none;
}

@media (max-width: 1180px) {
  .account-page {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .account-page {
    padding: 16px;
  }

  .account-side,
  .account-main {
    padding: 20px;
  }

  .account-side h1 {
    font-size: 34px;
  }

  .notice-strip-item,
  .audit-item {
    flex-direction: column;
    align-items: flex-start;
  }

  .account-grid {
    grid-template-columns: 1fr;
  }
}
</style>
