# API 使用说明（当前 MVP）

基址：`https://localhost:8444/api/v1`。以下是当前实现的主要接口，精确字段校验以各模块 Controller / Service 为准。时间使用 ISO-8601 带时区格式，资源 ID 和幂等键均为 UUID。

## 会话、错误与重试

先 `GET /auth/session` 获取 `data.csrf` 与当前身份。保留会话 Cookie，每个写请求发送 `Content-Type: application/json`、`X-CSRF-Token`；即使没有业务字段，也发送 `{}`。用户身份由服务端 Session 取得，不接受客户端通过普通业务字段指定操作者。

成功 JSON：`{"data":{...},"requestId":"..."}`；失败 JSON：`{"code":"...","message":"...","requestId":"..."}`。CSV 导出是例外，响应为 text/csv。应用返回 `X-Request-ID`，可按该 ID 查日志。

401 表示尚未登录，403 表示 CSRF 或权限/身份条件不满足，404 也用于隐藏无权读取的对象，409 表示业务或版本冲突，413 为请求体超过 64 KiB，429 为写入过频，503 为瞬时数据库错误重试耗尽。客户端必须检查 HTTP 状态和响应内容，不能只以请求发出为成功。

发布、申请、批准、退出需要 `Idempotency-Key`。同一操作者、操作、键和相同内容返回原结果；改变内容应使用新键。网络中断或 503 后原操作重试保留原键；409 时先刷新当前资源，避免盲目覆盖。聊天使用独立的 `clientMessageId` 去重。治理处置同样要求 Idempotency-Key，将其作为处置标识。

活动列表、消息和通知的分页响应有 `items / hasMore / nextCursor`，pageSize 默认 20、上限 50。游标带签名并绑定查询上下文；改变筛选条件时清空游标。

## 身份与资料

| 方法与路径 | 用途 |
| --- | --- |
| GET `/auth/session` | 身份、CSRF token、是否有 demo 适配器 |
| GET `/auth/demo/accounts` | 本机虚拟演示账号 |
| POST `/auth/demo` | `{userId}` 登录，随后重新获取 session |
| POST `/auth/logout` | 使当前会话失效 |
| GET / PATCH `/me/profile` | 获取/修改本人资料 |
| PATCH `/me/privacy` | 联系方式和精确地点分享选项，默认隐藏 |
| DELETE `/me/account` | `{confirm:true}` 注销、撤销会话、擦除相关私密资料；不可用作日常退出登录 |

## 活动与组队

| 方法与路径 | 用途 / 关键字段 |
| --- | --- |
| GET `/activities` | 搜索和分页；q、type、campus、tag、from、to、minRemaining、sort、cursor、pageSize |
| POST `/activities/search` | 距离搜索，需 locationConsent=true、lat/lon；坐标不放 URL |
| GET `/activities/{id}` | 活动详情；按请求者权限裁剪私密字段 |
| POST `/activities` | 发布；需幂等键 |
| PATCH `/activities/{id}` | 修改；expectedVersion、reason 及变更字段 |
| POST `/activities/{id}/cancellation` | 取消；expectedVersion、reason |
| POST `/activities/{id}/completion` | 开始后可结束；expectedVersion |
| POST `/activities/{id}/archive` | 已结束/取消后归档；expectedVersion |
| POST `/activities/{id}/applications` | 申请；message 可选，需幂等键 |
| GET `/activities/{id}/applications` | 按权限查看申请及历史 |
| POST `/activities/{id}/applications/{aid}/approval` | 批准；round、expectedVersion，需幂等键 |
| POST `/activities/{id}/applications/{aid}/withdrawal` | 申请者撤回；round、expectedVersion |
| POST `/activities/{id}/applications/{aid}/rejection` | 发起人拒绝；round、expectedVersion、reason |
| GET `/activities/{id}/participations` | 当前成员与状态 |
| GET `/me/participations` | 本人活动、参与和申请记录 |
| PATCH `/activities/{id}/participations/me/confirmation` | expectedVersion、status=CONFIRMED 或 LEAVE |
| POST `/activities/{id}/participations/me/exit` | 退出；reason、幂等键 |
| POST `/activities/{id}/participations/me/checkin` | 自报签到；status=SELF_REPORTED |
| POST `/activities/{id}/participations/{uid}/removal` | 发起人移除成员；reason |

类型为 STUDY / SPORT / TRAVEL / MEAL / OTHER，排序为 START_TIME / DISTANCE。from 包含、to 不包含。距离排序必须走已授权的 POST 搜索。

发布 JSON 示例（将时间调整为未来，且避免本人其他活动冲突）：

```json
{
  "type": "STUDY", "title": "图书馆自习一小时", "description": "各自学习，结束时交流进度",
  "campus": "主校区", "tags": ["自习"], "capacity": 3,
  "startAt": "2026-10-20T10:00:00+08:00", "endAt": "2026-10-20T11:00:00+08:00",
  "deadline": "2026-10-20T09:40:00+08:00", "confirmationDeadline": "2026-10-20T09:50:00+08:00",
  "publicLocation": "图书馆", "exactLocation": "三楼讨论区"
}
```

时间须满足报名截止 ≤ 确认截止 ≤ 开始 < 结束；开始必须在未来。capacity 含发起人。批准 body 示例：`{"round":1,"expectedVersion":0}`，版本取申请的 version；响应包含 status、groupId、participationId、occupied、capacity 及申请/活动版本。

## V3 收藏与搜索历史

| 方法与路径 | 用途 |
| --- | --- |
| POST / DELETE `/activities/{id}/favorite` | 收藏 / 取消收藏；返回 `favorited` 与收藏数 |
| GET `/activities/favorites/mine` | 本人收藏列表，游标分页，含活动摘要 |
| GET / DELETE `/activities/search-history` | 查询 / 清空本人最近 20 条搜索历史；删除返回 200 JSON |
| DELETE `/activities/search-history/{keyword}` | 删除一条搜索历史；返回 200 JSON |

收藏和搜索历史都只对当前登录用户可见。清空/删除不再返回 204 空响应，客户端可以按统一 `{data,requestId}` 解析。

## 临时群、反馈与通知

| 方法与路径 | 用途 |
| --- | --- |
| GET `/groups/{id}/members` | 当前有群权限的请求者查看成员 |
| GET `/groups/{id}/messages` | 游标消息页，附 pin、accessMode、ownerId |
| POST `/groups/{id}/messages` | 文字：`{clientMessageId, body}`；图片：`{clientMessageId, mediaType:"IMAGE", mediaUrl}`，mediaUrl 必须来自本人上传 |
| POST `/groups/{id}/messages/{messageId}/recall` | 发送者 2 分钟内撤回；服务端清空正文/媒体，仅保留 recalledAt |
| PUT `/groups/{id}/pin` | 发起人设置置顶；`{body, expectedVersion}` |
| POST `/media/upload` | multipart `file`，≤5MB，JPEG/PNG/GIF/WebP；返回 `/uploads/<uuid>.<ext>` 并记录 media_file |
| GET / PUT `/notifications/preferences` | 通知偏好，含 pendingReminder、feedbackReminder、groupMessage 等 |
| GET `/activities/{id}/feedback` | 查询反馈资格、本人已有反馈及可评价对象 |
| POST `/activities/{id}/feedback` | completion、experience、reason、ratings |
| GET `/notifications` | unreadOnly、cursor、pageSize；仅本人通知 |
| PUT `/notifications/{id}/read` | 本人通知标记已读，可重复 |
| GET `/admin/delivery` | 管理员查看投递任务概况 |

反馈 completion=COMPLETED / PARTIAL / NOT_COMPLETED，experience 为 1～5，未完全完成时需原因。ratings 可省略，元素为 `{targetUserId,score,comment}`；score 为 1～5，目标来自结束快照，每个目标只能出现一次。每个活动本人最多提交一次反馈。

群访问由服务端实时校验，结束/取消后可变为只读，移除/退出/解散后相应访问被拒绝。浏览器本地仍存有 groupId 不代表仍有权限。

## 举报、治理与统计

| 方法与路径 | 用途 |
| --- | --- |
| POST / GET `/reports` | 提交举报（targetType、targetId、reason，evidence可选）/ 查看本人相关举报 |
| GET `/admin/reports` | 管理员举报队列及 SLA 信息 |
| POST `/admin/reports/{id}/acceptance` | 受理，expectedVersion、reason |
| POST `/admin/reports/{id}/processing` | 开始处理，expectedVersion、reason |
| POST `/admin/reports/{id}/decision` | 处置，expectedVersion、action、reason、处置对象；非DISMISS需evidenceReviewed=true；需幂等键 |
| POST `/reports/{id}/appeals` | 相关当事人申诉，理由必填 |
| POST `/admin/appeals/{id}/decision` | 复核，result=UPHELD 或 REVOKED、reason、expectedVersion |
| GET `/admin/audit` | 管理员查看审计记录 |
| GET `/admin/statistics` | from/to/type 的聚合结果及 asOf |
| GET `/admin/statistics/export` | 相同口径 CSV |

处置包括不予处置、屏蔽活动/消息、取消活动、解散群、限制账号、移除成员、确认失约；每种 action 的业务对象和资格校验不同。管理员访问同样经过 Session 与 CSRF，客户端不能通过显示管理员按钮获得权限。

本页是原型交接说明，正式外部集成时应冻结完整 OpenAPI 契约、响应兼容策略和真实认证流程。
