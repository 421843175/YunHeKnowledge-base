export function saveToken(token) {
  localStorage.setItem('ai_enterprise_token', token)
  window.dispatchEvent(new Event('ai-account-changed'))
}

export function getToken() {
  return localStorage.getItem('ai_enterprise_token') || ''
}

export function clearToken() {
  localStorage.removeItem('ai_enterprise_token')
  window.dispatchEvent(new Event('ai-account-changed'))
}

export function saveProfile(profile) {
  localStorage.setItem('ai_enterprise_profile', JSON.stringify(profile || {}))
  window.dispatchEvent(new Event('ai-account-changed'))
}

export function getProfile() {
  try {
    return JSON.parse(localStorage.getItem('ai_enterprise_profile') || '{}')
  } catch {
    return {}
  }
}

export function clearProfile() {
  localStorage.removeItem('ai_enterprise_profile')
  window.dispatchEvent(new Event('ai-account-changed'))
}

export function savePendingAuditCount(count) {
  localStorage.setItem('ai_pending_audit_count', String(Number(count) || 0))
  window.dispatchEvent(new Event('ai-account-changed'))
}

export function getPendingAuditCount() {
  return Number(localStorage.getItem('ai_pending_audit_count') || 0)
}

export function clearPendingAuditCount() {
  localStorage.removeItem('ai_pending_audit_count')
  window.dispatchEvent(new Event('ai-account-changed'))
}
