import { reactive } from 'vue'
export type Data = Record<string, any>
export const session = reactive({ user: null as Data | null, csrf: '', demo: false })
export class ApiError extends Error {
  constructor(public code: string, message: string, public requestId: string, public status: number) { super(message) }
}
const pending = new Map<string, string>()
export async function api<T = any>(path: string, method = 'GET', body?: unknown, idempotent = false): Promise<T> {
  if (method !== 'GET' && method !== 'HEAD' && !session.csrf) await refreshSession()
  const signature = `${method}:${path}:${JSON.stringify(body)}`
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET' && method !== 'HEAD') headers['Content-Type'] = 'application/json'
  if (method !== 'GET') headers['X-CSRF-Token'] = session.csrf
  if (idempotent) {
    if (!pending.has(signature)) pending.set(signature, crypto.randomUUID())
    headers['Idempotency-Key'] = pending.get(signature)!
  }
  let response: Response
  try { response = await fetch('/api/v1' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' }) }
  catch { throw new ApiError('NETWORK_ERROR', '连接中断，输入已保留。请恢复连接后重试。', '', 0) }
  let payload: Data
  try { payload = await response.json() }
  catch { throw new ApiError('INVALID_RESPONSE', '服务返回了无法识别的内容，请稍后重试。', '', response.status) }
  if (!response.ok) {
    if (payload.code === 'AUTH_REQUIRED') { session.user = null; session.csrf = ''; pending.clear() }
    throw new ApiError(payload.code || 'REQUEST_FAILED', payload.message || '操作失败', payload.requestId || '', response.status)
  }
  if (!Object.hasOwn(payload, 'data')) throw new ApiError('INVALID_RESPONSE', '服务响应缺少数据。', payload.requestId || '', response.status)
  if (idempotent) pending.delete(signature)
  return payload.data as T
}
export async function refreshSession() {
  try { Object.assign(session, await api('/auth/session')) }
  catch (e) {
    // A revoked session is invalidated by the server on its first use.
    if (!(e instanceof ApiError) || e.code !== 'AUTH_REQUIRED') throw e
    Object.assign(session, await api('/auth/session'))
  }
}
export async function login(userId: string) { await api('/auth/demo', 'POST', { userId }); await refreshSession() }

// ===== 活动收藏 =====
export async function favoriteActivity(id: string) { return api<{favorited:boolean,count:number}>(`/activities/${id}/favorite`, 'POST') }
export async function unfavoriteActivity(id: string) { return api<{favorited:boolean,count:number}>(`/activities/${id}/favorite`, 'DELETE') }
export async function myFavorites(cursor?: string) { return api<{items:Data[],nextCursor:string|null}>(`/activities/favorites/mine${cursor?'?cursor='+encodeURIComponent(cursor):''}`) }

// ===== 消息撤回 =====
export async function recallMessage(groupId: string, messageId: string) { return api(`/groups/${groupId}/messages/${messageId}/recall`, 'POST') }

// ===== 图片上传 =====
export async function uploadImage(file: File): Promise<{url:string,fileName:string,contentType:string,size:number}> {
  const form = new FormData()
  form.append('file', file)
  const headers: Record<string,string> = { 'X-CSRF-Token': session.csrf }
  const res = await fetch('/api/v1/media/upload', { method: 'POST', headers, body: form, credentials: 'same-origin' })
  const payload = await res.json()
  if (!res.ok) throw new ApiError(payload.code || 'UPLOAD_FAILED', payload.message || '上传失败', payload.requestId || '', res.status)
  return payload.data
}

// ===== 搜索历史 =====
export async function getSearchHistory() { return api<Data[]>('/activities/search-history') }
export async function clearSearchHistory() { return api('/activities/search-history', 'DELETE') }
export async function deleteSearchHistory(keyword: string) { return api(`/activities/search-history/${encodeURIComponent(keyword)}`, 'DELETE') }

// ===== 通知偏好 =====
export async function getNotificationPreferences() { return api<Data>('/notifications/preferences') }
export async function updateNotificationPreferences(prefs: Data) { return api<Data>('/notifications/preferences', 'PUT', prefs) }
export const labels: Record<string, string> = {
 STUDY: '学习自习', SPORT: '运动健身', TRAVEL: '出行探索', MEAL: '吃饭搭伙', OTHER: '兴趣生活',
 OPEN: '招募中', IN_PROGRESS: '进行中', ENDED: '已结束', CANCELLED: '已取消', ARCHIVED: '已归档',
 PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝', WITHDRAWN: '已撤回', EXPIRED: '已过期',
 ACTIVE: '已加入', LEFT: '已退出', REMOVED: '已移除', UNCONFIRMED: '待确认', CONFIRMED: '已确认', LEAVE: '已请假',
 SUBMITTED: '已提交', ACCEPTED: '已受理', PROCESSING: '处理中', RESOLVED: '已处理', APPEAL_PENDING: '申诉待复核', APPEAL_RESOLVED: '申诉已复核',
 COMPLETED: '已完成', PARTIAL: '部分完成', NOT_COMPLETED: '未完成', SELF_REPORTED: '已自报签到', NOT_REPORTED: '未签到',
 USER: '成员', ADMIN: '管理员', OWNER: '发起人', MEMBER: '成员', NORMAL: '正常', DISABLED: '已受限', READ_ONLY: '只读', BLOCKED: '已解散',
 HIDE_ACTIVITY: '屏蔽活动', CANCEL_ACTIVITY: '取消活动', DISSOLVE_GROUP: '解散小组', DISABLE_USER: '限制账号', REMOVE_MEMBER: '移除成员', CONFIRM_NO_SHOW: '确认失约', HIDE_MESSAGE: '屏蔽消息', DISMISS: '不予处置', UPHOLD: '维持处置', REVOKE: '撤销处置'
}
export const label = (v: string) => labels[v] || v
export const date = (value: string) => value ? new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) : '—'
export function localTime(value: string | Date) { const d = new Date(value); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16) }
export function describeError(e: unknown) { return e instanceof ApiError ? `${e.message}${e.requestId ? `（请求编号 ${e.requestId}）` : ''}` : e instanceof Error ? e.message : '操作失败，请重试' }
