CREATE TABLE conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会话ID，一个聊天窗口',
    user_id BIGINT NULL COMMENT '用户ID（预留）',
    current_playlist_id BIGINT NULL COMMENT '当前播放列表ID',
    STATUS VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / FINISHED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 会话，一个聊天窗口';

CREATE TABLE playlist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '播放列表ID',
    conversation_id BIGINT NOT NULL COMMENT '所属会话ID',
    NAME VARCHAR(255) NOT NULL COMMENT '播放列表名称',
    target_count INT NOT NULL COMMENT '目标歌曲数量',
    actual_count INT NOT NULL DEFAULT 0 COMMENT '当前已加入歌曲数',
    STATUS VARCHAR(32) NOT NULL DEFAULT 'BUILDING' COMMENT 'BUILDING / DONE / PARTIAL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    CONSTRAINT fk_playlist_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation(id)
        ON DELETE CASCADE
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='播放列表';

CREATE TABLE video (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '视频ID',
    platform VARCHAR(32) NOT NULL COMMENT '平台，如 bilibili',
    platform_vid VARCHAR(128) NOT NULL COMMENT '平台视频ID，如 BVID',
    title VARCHAR(512) NOT NULL COMMENT '视频标题',
    tags TEXT COMMENT '视频标签',
    description TEXT COMMENT '视频简介',
    duration_sec INT COMMENT '视频总时长（秒）',
    url VARCHAR(1024) NOT NULL COMMENT '视频播放地址',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_platform_vid (platform, platform_vid)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='视频缓存表（去重用）';

CREATE TABLE music_unit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '歌曲ID（抽象音乐单元）',
    title VARCHAR(255) NOT NULL COMMENT '歌曲名称',
    artist VARCHAR(255) COMMENT '歌手',
    duration_sec INT COMMENT '歌曲时长（秒）',
    style VARCHAR(255) COMMENT '风格（Agent 总结）',
    scene VARCHAR(255) COMMENT '适合场景（Agent 总结）',
    source VARCHAR(64) NOT NULL COMMENT '来源平台，如 bilibili',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_title (title)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='音乐单元（一首歌）';

CREATE TABLE playlist_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '播放列表项ID',
    playlist_id BIGINT NOT NULL COMMENT '播放列表ID',
    music_unit_id BIGINT NOT NULL COMMENT '歌曲ID',
    video_id BIGINT NOT NULL COMMENT '来源视频ID',
    POSITION INT NOT NULL COMMENT '排序位置，数值越小越靠前',
    added_reason VARCHAR(255) COMMENT '加入原因 / Agent 解释',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_playlist_position (playlist_id, POSITION),
    CONSTRAINT fk_item_playlist
        FOREIGN KEY (playlist_id) REFERENCES playlist(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_item_music
        FOREIGN KEY (music_unit_id) REFERENCES music_unit(id),
    CONSTRAINT fk_item_video
        FOREIGN KEY (video_id) REFERENCES video(id)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='播放列表项（有序）';

CREATE TABLE agent_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Agent 执行ID',
    conversation_id BIGINT NOT NULL COMMENT '所属会话',
    input_text TEXT NOT NULL COMMENT '用户原始输入',
    STATUS VARCHAR(32) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / DONE / FAILED',
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at DATETIME COMMENT '结束时间',
    INDEX idx_conversation_id (conversation_id),
    CONSTRAINT fk_agent_run_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation(id)
        ON DELETE CASCADE
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行记录';

CREATE TABLE agent_stage_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '阶段事件ID',
    agent_run_id BIGINT NOT NULL COMMENT '所属 AgentRun',
    stage VARCHAR(64) NOT NULL COMMENT '阶段名称，如 INTENT / SEARCH / JUDGE',
    content TEXT NOT NULL COMMENT '对用户可见的阶段说明',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_run_stage (agent_run_id, stage),
    CONSTRAINT fk_stage_agent_run
        FOREIGN KEY (agent_run_id) REFERENCES agent_run(id)
        ON DELETE CASCADE
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 阶段事件日志';

CREATE TABLE agent_stage_def (
    id INT PRIMARY KEY AUTO_INCREMENT,
    stage_code VARCHAR(64) NOT NULL UNIQUE COMMENT '阶段标识',
    display_name VARCHAR(128) NOT NULL COMMENT '展示名称',
    description TEXT COMMENT '阶段说明',
    display_order INT NOT NULL COMMENT '展示顺序'
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 阶段定义';

INSERT INTO agent_stage_def (stage_code, display_name, description, display_order) VALUES
('INTENT', '理解用户需求', '解析用户输入，提取关键词、数量、风格偏好', 1),
('SEARCH', '搜索视频资源', '在 B 站搜索可能包含目标音乐的视频', 2),
('VIDEO_JUDGE', '分析视频内容', '进入视频页判断歌曲数量与相关性', 3),
('FILTER', '筛选歌曲', '根据规则筛选可加入播放列表的歌曲', 4),
('ASSEMBLE', '构建播放列表', '按顺序组织最终歌单', 5),
('SUMMARY', '生成风格与场景总结', '总结整体风格与适用场景', 6);

CREATE TABLE music_tag_def (
    id INT PRIMARY KEY AUTO_INCREMENT,
    tag_type VARCHAR(32) NOT NULL COMMENT 'STYLE / SCENE',
    tag_code VARCHAR(64) NOT NULL UNIQUE COMMENT '标签编码',
    display_name VARCHAR(128) NOT NULL COMMENT '展示名称'
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='音乐标签定义';

INSERT INTO music_tag_def (tag_type, tag_code, display_name) VALUES
('STYLE', 'PURE', '纯音乐'),
('STYLE', 'ACG', 'ACG'),
('STYLE', 'LOFI', 'Lo-Fi'),
('SCENE', 'STUDY', '学习'),
('SCENE', 'WORK', '工作'),
('SCENE', 'SLEEP', '助眠');

CREATE TABLE platform_def (
    id INT PRIMARY KEY AUTO_INCREMENT,
    platform_code VARCHAR(32) UNIQUE,
    display_name VARCHAR(64),
    base_url VARCHAR(255)
);

CREATE TABLE user_video_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL COMMENT '在哪个会话下产生的反馈',
    video_id BIGINT NOT NULL COMMENT '被反馈的视频',
    feedback_type VARCHAR(32) NOT NULL COMMENT 'LIKE / DISLIKE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conv_video (conversation_id, video_id),
    CONSTRAINT fk_feedback_video
        FOREIGN KEY (video_id) REFERENCES video(id),
    CONSTRAINT fk_feedback_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation(id)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='用户对视频的显式反馈';

CREATE TABLE music_unit_weight (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    music_unit_id BIGINT NOT NULL COMMENT '歌曲ID',
    weight_score DOUBLE NOT NULL DEFAULT 0 COMMENT '权重分数',
    last_updated DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_music (music_unit_id),
    CONSTRAINT fk_weight_music
        FOREIGN KEY (music_unit_id) REFERENCES music_unit(id)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='歌曲全局权重';

CREATE TABLE user_tag_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL COMMENT '会话上下文',
    tag_code VARCHAR(64) NOT NULL COMMENT '标签编码（STYLE / SCENE）',
    preference_score DOUBLE NOT NULL DEFAULT 0 COMMENT '偏好权重',
    last_updated DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conv_tag (conversation_id, tag_code)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COMMENT='用户在当前会话下的标签偏好';

ALTER TABLE playlist_item
ADD COLUMN user_liked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户是否在当前会话中喜欢';