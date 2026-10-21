-- 功能1: 活动收藏
CREATE TABLE activity_favorite(
  user_id uuid NOT NULL REFERENCES user_account(id),
  activity_id uuid NOT NULL REFERENCES activity(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id, activity_id)
);
CREATE INDEX activity_favorite_user ON activity_favorite(user_id, created_at DESC);

-- 功能2: 消息撤回 - 添加 recalled_at 字段
ALTER TABLE message ADD COLUMN recalled_at timestamptz;

-- 功能3: 图片消息 - 添加媒体字段
ALTER TABLE message ADD COLUMN media_type varchar(16);
ALTER TABLE message ADD COLUMN media_url text;
ALTER TABLE message ALTER COLUMN body DROP NOT NULL;
ALTER TABLE message DROP CONSTRAINT message_body_check;
ALTER TABLE message ADD CONSTRAINT message_content_check CHECK (
  (recalled_at IS NOT NULL) OR (media_type IS NOT NULL AND media_url IS NOT NULL) OR (body IS NOT NULL AND length(trim(body)) > 0)
);

-- 功能4: 搜索历史
CREATE TABLE search_history(
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES user_account(id),
  keyword varchar(100) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX search_history_user ON search_history(user_id, created_at DESC);

-- 功能5: 通知偏好设置
CREATE TABLE notification_preference(
  user_id uuid PRIMARY KEY REFERENCES user_account(id),
  activity_published boolean NOT NULL DEFAULT true,
  application_submitted boolean NOT NULL DEFAULT true,
  application_decided boolean NOT NULL DEFAULT true,
  activity_changed boolean NOT NULL DEFAULT true,
  activity_cancelled boolean NOT NULL DEFAULT true,
  start_reminder boolean NOT NULL DEFAULT true,
  deadline_reached boolean NOT NULL DEFAULT true,
  feedback_reminder boolean NOT NULL DEFAULT true,
  governance boolean NOT NULL DEFAULT true,
  group_message boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- 静态资源存储目录 (图片消息)
CREATE TABLE media_file(
  id uuid PRIMARY KEY,
  uploader_id uuid NOT NULL REFERENCES user_account(id),
  file_name varchar(255) NOT NULL,
  content_type varchar(128) NOT NULL,
  file_size bigint NOT NULL,
  storage_path text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
