import { reactive } from 'vue'
export type Data = Record<string, any>
export const session = reactive({ user: null as Data | null, csrf: '', demo: false })
export class ApiError extends Error {
  constructor(public code: string, message: string, public requestId: string, public status: number) { super(message) }
}
const pending = new Map<string, string>()
export async function api<T = any>(path: string, method = 'GET', body?: unknown, idempotent = false): Promise<T> {
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
  if (!response.ok) throw new ApiError(payload.code || 'REQUEST_FAILED', payload.message || '操作失败', payload.requestId || '', response.status)
  if (!Object.hasOwn(payload, 'data')) throw new ApiError('INVALID_RESPONSE', '服务响应缺少数据。', payload.requestId || '', response.status)
  if (idempotent) pending.delete(signature)
  return payload.data as T
}
export async function refreshSession() { Object.assign(session, await api('/auth/session')) }
export async function login(userId: string) { await api('/auth/demo', 'POST', { userId }); await refreshSession() }
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
