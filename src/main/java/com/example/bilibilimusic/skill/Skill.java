package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;

/**
 * Skill 基础接口
 * 所有 Skill 必须实现此接口，确保可组合性
 */
public interface Skill {
    
    /**
     * 执行 Skill
     * @param context Agent 上下文
     * @return 是否执行成功
     */
    boolean execute(PlaylistContext context);
    
    /**
     * Skill 名称（用于日志与调试）
     */
    String getName();
}
