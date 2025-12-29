# 数据库集成配置说明

## ✅ 已完成的配置

### 1. 依赖配置 (pom.xml)
- ✅ MySQL驱动：`mysql-connector-j` (Spring Boot 3.3自动管理版本)
- ✅ MyBatis-Plus：`mybatis-plus-boot-starter` 3.5.7

### 2. 数据库配置 (application.yml)
```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/bilibili?characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: 123456

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
  global-config:
    db-config:
      id-type: auto
```

### 3. 实体类 (Entity)
已创建以下实体类（位于 `com.example.bilibilimusic.entity`）：
- ✅ `Conversation` - 会话实体
- ✅ `Playlist` - 播放列表实体
- ✅ `Video` - 视频缓存实体
- ✅ `MusicUnitEntity` - 音乐单元实体
- ✅ `PlaylistItem` - 播放列表项实体

### 4. Mapper接口
已创建以下Mapper接口（位于 `com.example.bilibilimusic.mapper`）：
- ✅ `ConversationMapper`
- ✅ `PlaylistMapper`
- ✅ `VideoMapper`
- ✅ `MusicUnitMapper`
- ✅ `PlaylistItemMapper`

### 5. 主类配置
- ✅ 已添加 `@MapperScan("com.example.bilibilimusic.mapper")` 注解

### 6. 服务层
- ✅ `DatabaseService` - 数据库持久化服务，提供以下功能：
  - 创建/获取活跃会话
  - 创建播放列表
  - 保存/更新视频信息（自动去重）
  - 添加歌曲到播放列表
  - 完成播放列表构建

### 7. Agent集成
- ✅ `PlaylistAgent` 已集成数据库持久化：
  - 执行任务时自动创建会话和播放列表
  - 流式发送视频时自动保存到数据库
  - 任务完成时更新播放列表状态

## 📋 使用前准备

### 1. 创建数据库
```sql
CREATE DATABASE bilibili CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 执行建表语句
在MySQL中执行 `backup.sql` 文件中的建表语句：
```bash
mysql -u root -p bilibili < backup.sql
```

或者在MySQL客户端中：
```sql
USE bilibili;
SOURCE /path/to/backup.sql;
```

## 🎯 核心功能

### 自动持久化
当用户通过前端请求歌单时，系统会自动：

1. **创建会话** - 每个聊天窗口对应一个会话
2. **创建播放列表** - 根据用户需求创建播放列表记录
3. **保存视频信息** - 流式处理时实时保存视频到数据库（自动去重）
4. **创建音乐单元** - 为每首歌创建独立的音乐单元记录
5. **关联播放列表项** - 建立播放列表和歌曲的关联关系
6. **更新状态** - 任务完成时更新播放列表状态（DONE/PARTIAL）

### 数据去重
- 视频通过 `platform` + `platform_vid`（BVID）进行去重
- 相同视频不会重复存储，只会更新元数据

### 流式处理
- 每判断完一个视频立即保存到数据库
- 不需要等待所有视频处理完成
- 符合流式处理的用户偏好

## 🔍 查询示例

### 查看当前活跃会话
```sql
SELECT * FROM conversation WHERE status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1;
```

### 查看最新的播放列表
```sql
SELECT * FROM playlist ORDER BY created_at DESC LIMIT 10;
```

### 查看播放列表中的歌曲（按位置排序）
```sql
SELECT 
  pi.position,
  mu.title,
  mu.artist,
  v.url,
  pi.added_reason
FROM playlist_item pi
JOIN music_unit mu ON pi.music_unit_id = mu.id
JOIN video v ON pi.video_id = v.id
WHERE pi.playlist_id = 1
ORDER BY pi.position;
```

### 查看视频统计
```sql
SELECT 
  COUNT(*) as total_videos,
  COUNT(DISTINCT platform_vid) as unique_videos
FROM video;
```

## ⚠️ 注意事项

1. **数据库连接**
   - 确保MySQL服务正在运行
   - 检查数据库用户名和密码是否正确
   - 确认数据库 `bilibili` 已创建

2. **字符集**
   - 数据库和表都使用 `utf8mb4` 字符集
   - 支持存储emoji和特殊字符

3. **时区配置**
   - JDBC URL中包含 `serverTimezone=Asia/Shanghai`
   - 确保时间字段正确存储

4. **性能优化**
   - 已在关键字段创建索引（如 `platform_vid`）
   - 使用了外键约束保证数据完整性

## 🚀 后续扩展

可以基于已有的表结构添加更多功能：
- 用户反馈记录（`user_video_feedback`表）
- 音乐权重计算（`music_unit_weight`表）
- 用户标签偏好（`user_tag_preference`表）
- Agent执行日志（`agent_run`, `agent_stage_event`表）
