import request from './request'

export function login(data) {
  return request.post('/users/login', data)
}

export function register(data) {
  return request.post('/users/register', data)
}

export function applyEnterprise(data) {
  return request.post('/enterprises/apply', data)
}

export function listEnterprises() {
  return request.get('/enterprises/list')
}

export function getPendingAuditUsers() {
  return request.get('/users/audit/pending')
}

export function auditUser(data) {
  return request.post('/users/audit', data)
}
