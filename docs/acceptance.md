# 第 5 周验收对照与真实验证记录

范围：以主要业务闭环、数据一致性和真实可运行验证为本轮交付；用户已明确将后续优化与集成留在后面几周。保留团队原指标，尚未测到的项目不标为通过。以下“已覆盖”仅指表中具体场景的自动化证据，不等于整个需求的所有可能输入均已验收。

## 来源

- 团队《校园搭子与搭伙行动平台_需求分析文档 (3).docx》：FR-01～FR-10、NFR-01～NFR-08。
- 团队《校园搭子与搭伙行动平台_架构设计草案.docx》：七模块单体、Vue / Spring Boot / PostgreSQL、事务内通知与提交后投递。
- 团队《校园搭子与搭伙行动平台详细设计文档docx.docx》：具体状态、接口、事务规则以及 **T-01～T-26**。
- 团队周计划图：本轮为第 5 周 MVP 原型。

## 本次结果

- 后端/规则/架构测试：2026-10-12 04:06:03 +08:00；**41 tests，0 failures，0 errors，0 skipped**。
- 最终浏览器烟测：2026-10-12 04:15:17 +08:00；**13 个检查通过**，pageErrors=[]，serverErrors=[]。
- 源数据：[tests.json](evidence/tests.json)、[browser-smoke.json](evidence/browser-smoke.json)、[smoke-failure-guard.json](evidence/smoke-failure-guard.json)。Surefire 原始 XML 在 `server/target/surefire-reports`。
- 环境：Windows、PowerShell 7、JDK 25.0.1（release 21）、PostgreSQL 18.6、Spring Boot 3.5.16、Flyway 11.14.0；浏览器为实际安装的 Edge，经过 Nginx HTTPS 8444。
- 39 项集成测试使用专用真实 PostgreSQL；2 项为独立规则与 ArchUnit 检查。没有内存数据库替代锁语义、没有外层测试事务代替真实提交、没有条件跳过。
- 时间边界测试使用可控 Clock 并断言边界前后行为；调度方法由测试显式驱动。这不是实际等待 24 小时或证明线上调度永不延迟。HTTP 集成测试在随机本地端口关闭 TLS；最终浏览器烟测补充验证 HTTPS 运行链路。

### 防假阳性措施

`test.ps1` 启动先写 RUNNING / passed=false，检查 Maven 退出码、当前执行的新鲜 XML、每套件测试数>0、零跳过/错误/失败，全部满足才写 PASSED。任何失败写 FAILED 并退出失败。

`smoke.mjs` 在加载 Playwright、读取配置和启动浏览器前将报告设为 false；每步同时检查页面、HTTP 或实际数据库结果，捕获页面异常及 5xx；失败保存原因并退出 1。额外实际注入了缺失模块，确认原成功记录会失效，再恢复依赖重跑。

最终反馈烟测核验已有的单次反馈，报告标记 `existing submission confirmed`，不记作本轮新提交。新提交、重复拒绝及 24 小时截止边界由 LifecycleTest 覆盖。100 条通知的时限测试仅使用模拟推送适配器，不能代表真实渠道 99% SLA。

## 功能需求

| 需求 | 本轮实现 / 证据 |
| --- | --- |
| FR-01 身份资料 | demo 身份、已认证门禁、资料/隐私、注销及会话撤销；HttpSecurityTest、GovernancePrivacyTest；真实校内认证待接入 |
| FR-02 发布管理 | 发布、修改、取消、结束、归档、版本与变更通知；LifecycleTest、RetryDiscoveryTest、浏览器闭环 |
| FR-03 发现筛选 | 关键词、类型、校区、标签、时间、余量、距离授权、签名分页；RetryDiscoveryTest，桌面/360px 实际页面 |
| FR-04 申请组队 | 申请轮次、审核/拒绝/撤回、容量及时间冲突、幂等、建群；ApprovalTest、LifecycleTest |
| FR-05 组内沟通 | 当前群资格、消息、置顶、相同时间分页、退出撤权；HttpSecurityTest、ScheduledBoundaryTest、浏览器 |
| FR-06 提醒状态 | 确认、请假、退出、移除、签到、截止/开始/待审提醒；LifecycleTest、ScheduledBoundaryTest |
| FR-07 反馈评价 | 含 OWNER 的结束快照、24h、一次提交、同快照评价；LifecycleTest |
| FR-08 通知中心 | 站内通知、已读、去重、异步任务、租约/重试/隔离；DeliveryTest、ScheduledBoundaryTest；外部渠道为模拟 |
| FR-09 安全治理 | 举报、24h SLA、处置、申诉、审计、注销账号保护；GovernancePrivacyTest、ScheduledBoundaryTest、浏览器 |
| FR-10 运营统计 | 时间队列、零分母、取消与快照口径、聚合 CSV；GovernancePrivacyTest、浏览器 |

## 详细设计用例逐项映射

测试文件均位于 `server/src/test/java/edu/campusbuddy/`。

| 编号 | 本轮证据 | 覆盖判断 / 边界 |
| --- | --- | --- |
| T-01 最后名额竞争 | ApprovalTest.concurrentLastSeatHasOneWinner | 已覆盖并发批准，最终成员数及群唯一性断言 |
| T-02 同人重叠批准 | ApprovalTest 的并发冲突及 adjacentHalfOpenIntervalsAreAllowed | 已覆盖重叠互斥和相邻半开区间 |
| T-03 幂等重放/异内容 | ApprovalTest.successfulRequestReplaysWithoutExtraWritesAndRejectsChangedContent | 已覆盖原结果、409、写入数量 |
| T-04 通知写入异常回滚 | ApprovalTest.notificationInsertFailureRollsBackEveryApprovalWriteAndAllowsSameKeyRetry | 真实 PostgreSQL 故障注入，业务/群/通知/幂等整体回滚 |
| T-05 退出撤权 | LifecycleTest、HttpSecurityTest、浏览器 | 已覆盖退出后群 GET/POST 拒绝和名额释放 |
| T-06 截止门禁 | LifecycleTest.exactDeadlineRejectsEvenWithoutSchedulerAndExpirationIsTraceable | 不运行调度时截止即时拒绝；后续过期留痕 |
| T-07 变更与批准竞争 | LifecycleTest、RetryDiscoveryTest.editRacingWithApprovalNeverCreatesOverlappingParticipation | 已覆盖重大变更冲突与并发交错，最终参与无重叠 |
| T-08 渠道故障/接管/受理率 | DeliveryTest、ScheduledBoundaryTest.oneHundredCommittedMockChannelTasksAreAcceptedWithinFiveMinutes | 部分覆盖：故障、超时、租约、旧 token、30次耗尽、100条模拟任务；真实渠道99%/5min及发出后进程崩溃的供应商重复行为未验 |
| T-09 快照与24h | LifecycleTest、ScheduledBoundaryTest.snapshotWaitsForCommittedExitAndRejectsExitAfterEndWins | 已覆盖退出提交顺序、OWNER、快照不可变、一次提交及截止 |
| T-10 评价资格/失约 | LifecycleTest、GovernancePrivacyTest | 已覆盖非同快照目标拒绝、单方差评不自动认定失约 |
| T-11 越权/CSRF | HttpSecurityTest、GovernancePrivacyTest、ApprovalTest | 已覆盖已实现测试对象的权限、CSRF、密文裁剪；不外推为全部可能攻击均已验证 |
| T-12 举报闭环/SLA | GovernancePrivacyTest、ScheduledBoundaryTest、浏览器 | 已覆盖理由/证据、受理处置申诉、审计不可变、24h持久化逾期通知 |
| T-13 已读与隔离 | DeliveryTest.notificationReadIsIdempotentAndCannotReadOtherUsers | 已覆盖重复已读及跨用户拒绝 |
| T-14 备份恢复 | 无恢复演练证据 | 未执行；预留恢复库不等于验证恢复成功 |
| T-15 500VU/2000会话 | 无规模压测证据 | 未执行；41项测试和浏览器烟测不代替性能测试 |
| T-16 360px/首次流程 | 浏览器360px发现页与展开导航截图/宽度断言 | 部分覆盖；尚无真实手机矩阵及首次用户点击/耗时调查 |
| T-17 双倍规模扩容 | 无扩容证据 | 未执行 |
| T-18 版本兼容 | DeliveryTest.unknownEventVersionIsQuarantinedWithoutSending | 部分覆盖；未知事件隔离通过，独立旧客户端兼容测试待做 |
| T-19 禁用/解散/重复移除 | GovernancePrivacyTest、LifecycleTest、ScheduledBoundaryTest、HttpSecurityTest | 已覆盖对应权限与重复影响；注销身份撤销处罚不会复活 |
| T-20 统计/导出 | GovernancePrivacyTest.slaIsDetectableAndStatisticsUseFixedDenominators、浏览器 | 已覆盖空分母null、取消和成员快照、CSV聚合字段 |
| T-21 满员/冲突/重申 | LifecycleTest、ApprovalTest、浏览器 | 已覆盖PENDING不占位、满员/冲突拒绝、round递增及历史保留 |
| T-22 资料/隐私/筛选 | GovernancePrivacyTest、RetryDiscoveryTest | 已覆盖默认隐藏、授权字段、筛选和距离授权 |
| T-23 身份发布/首次使用 | HttpSecurityTest、浏览器发布/申请 | 部分覆盖；真实用户80%首次发布≤3分钟、列表至申请≤5分钟未测 |
| T-24 可用性/可观测 | 请求ID、API日志及烟测响应检查 | 部分覆盖；没有月度99.5%探测证据或完整日志安全审计 |
| T-25 签到/重大变更 | LifecycleTest.importantChangeResetsConfirmationNotMembershipAndCheckinIsIdempotent | 已覆盖签到幂等、版本变化与受影响者通知 |
| T-26 待审/截止/开始提醒 | ScheduledBoundaryTest.pendingDeadlineAndStartRemindersHaveExactBoundariesAndNoDuplicates | 已覆盖提前10/30分钟边界、过期通知、重复扫描去重 |

额外覆盖：真实 SQLSTATE 40001 的整事务重试/耗尽；分块请求体超过64KiB返回413；账号注销撤销多个会话及擦除私密数据；67条同时间消息分页无遗漏/重复；七模块依赖边界。测试曾发现100条正常任务只受理95条的线程队列竞争，调整有界队列后保留原断言重跑通过，未放宽通过条件。

## 非功能目标状态

| 需求 | 团队指标 / 本次状态 |
| --- | --- |
| NFR-01 性能 | 500VU、读写80/20；读P95≤2s、写P95≤3s、校园网络首屏≤3s、错误率≤1%、核心浏览成功≥99%。本轮未做压测。 |
| NFR-02 扩展 | 20,000账号、2,000并发在线、日均5,000浏览/更新、增长2倍不改业务代码。共享会话/任务已实现，容量和双倍规模未验证。 |
| NFR-03 可用 | 月可用性≥99.5%，非核心推送故障不回滚业务。故障分支已测，月度指标和真实渠道未验证。 |
| NFR-04 可靠 | 幂等、事务回滚、任务重试已测；每日备份、RPO≤24h、RTO≤4h未实施验收。 |
| NFR-05 安全 | 对象权限、CSRF、敏感字段密文、TLS链路已在上述范围验证；正式认证、生产密钥管理、保留期限与独立安全审查待集成。 |
| NFR-06 易用 | 中文状态、错误原因、移动布局已实现；常用路径≤5点击及首次用户效率目标需实际用户测试。 |
| NFR-07 兼容 | 本机Edge桌面和模拟360px视口通过；其他浏览器/真实手机尚未完整验证。 |
| NFR-08 可维护 | 七模块检查、迁移、API说明、requestId、审计和证据齐备；旧客户端兼容、完整监控和长期可用性验收待做。 |

## AI 审查意见落实

附录提出需求衔接、接口契约、异常分支和验证追踪仍需补足，本次通过实现和可执行证据回应，而不把 AI 自评分数当作通过证明：

| 关注点 | 本次落实 |
| --- | --- |
| 七模块及外部依赖边界 | 原生单体、接口包协作、ArchUnit；认证/推送明确标记 demo |
| 接口与数据库契约 | API v1、Session/CSRF、版本、幂等、唯一约束、Flyway迁移；交接 API 说明 |
| 并发与异常分支 | 最后名额、重叠活动、批准/变更竞争、截止延迟、回滚、租约接管、错误版本的实际断言 |
| 状态和权限一致 | 退出即时撤群权限、请假保位、重申留历史、结束快照、注销不复活 |
| 可观察与追踪 | requestId、审计、逾期事件、FR/NFR/T映射、JSON/XML与浏览器截图；未验证项保持显式 |

正式全量工程验收尚未完成；本轮交付结论限定为可运行、核心闭环已验证的第 5 周 MVP。
