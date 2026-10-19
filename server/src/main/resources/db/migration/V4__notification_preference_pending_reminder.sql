-- 将“待审核申请提醒”从“活动开始提醒”中拆出，避免关闭开始提醒时误关闭审核提醒。
ALTER TABLE notification_preference
  ADD COLUMN pending_reminder boolean NOT NULL DEFAULT true;
