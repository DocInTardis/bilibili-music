CREATE TABLE user_preference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT COMMENT '会话ID',
    preference_type VARCHAR(20) NOT NULL COMMENT '偏好类型：video/artist/keyword',
    preference_target VARCHAR(255) NOT NULL COMMENT '偏好目标（BV号/艺人名/关键词）',
    weight_score INT NOT NULL DEFAULT 0 COMMENT '权重分数',
    interaction_count INT NOT NULL DEFAULT 0 COMMENT '交互次数',
    last_updated DATETIME COMMENT '最后更新时间',
    created_at DATETIME COMMENT '创建时间',
    INDEX idx_conversation_type (conversation_id, preference_type),
    INDEX idx_conversation_target (conversation_id, preference_target)
) COMMENT='用户偏好表';

CREATE TABLE `agent_behavior_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `playlist_id` BIGINT COMMENT '播放列表ID',
  `conversation_id` BIGINT COMMENT '会话ID',
  `behavior_type` VARCHAR(50) COMMENT '行为类型',
  `node_name` VARCHAR(100) COMMENT '节点名称',
  `edge_name` VARCHAR(100) COMMENT '边名称',
  `source_node` VARCHAR(100) COMMENT '源节点',
  `target_node` VARCHAR(100) COMMENT '目标节点',
  `description` TEXT COMMENT '行为描述',
  `input_data` TEXT COMMENT '输入数据（JSON）',
  `output_data` TEXT COMMENT '输出数据（JSON）',
  `duration_ms` BIGINT COMMENT '执行时长（毫秒）',
  `success` BOOLEAN COMMENT '是否成功',
  `error_message` TEXT COMMENT '错误信息',
  `prompt_version` VARCHAR(50) COMMENT 'Prompt版本',
  `created_at` DATETIME COMMENT '创建时间',
  INDEX idx_playlist (playlist_id),
  INDEX idx_conversation (conversation_id),
  INDEX idx_behavior_type (behavior_type),
  INDEX idx_created_at (created_at)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='Agent行为日志';