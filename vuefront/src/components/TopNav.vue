<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { getPendingAuditCount, getProfile, getToken } from '../utils/token'

const token = ref(getToken())
const profile = ref(getProfile())
const pendingAuditCount = ref(getPendingAuditCount())

const syncAccountState = () => {
  token.value = getToken()
  profile.value = getProfile()
  pendingAuditCount.value = getPendingAuditCount()
}

onMounted(() => {
  window.addEventListener('storage', syncAccountState)
  window.addEventListener('focus', syncAccountState)
  window.addEventListener('ai-account-changed', syncAccountState)
  syncAccountState()
})

onUnmounted(() => {
  window.removeEventListener('storage', syncAccountState)
  window.removeEventListener('focus', syncAccountState)
  window.removeEventListener('ai-account-changed', syncAccountState)
})
</script>

<template>
  <header class="site-header">
    <RouterLink to="/" class="brand-link">
      <span class="brand-badge">JUPITER</span>
      <div>
        <strong>AI 企业服务平台</strong>
        <small>知识库 / 询问 / 企业文档</small>
      </div>
    </RouterLink>

    <nav class="site-nav">
      <RouterLink to="/" class="nav-link">知识库</RouterLink>
      <RouterLink v-if="!token" to="/login" class="nav-link nav-link-accent">登录 / 注册</RouterLink>
      <RouterLink v-else to="/mine" class="nav-link nav-link-accent nav-link-account">
        <span>{{ profile.nick || profile.username || '个人账户' }}</span>
        <span v-if="pendingAuditCount > 0" class="nav-dot"></span>
      </RouterLink>
    </nav>
  </header>
</template>
