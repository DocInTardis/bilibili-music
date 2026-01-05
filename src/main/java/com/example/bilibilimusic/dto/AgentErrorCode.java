package com.example.bilibilimusic.dto;

/**
 * Agent 统一异常码
 */
public enum AgentErrorCode {

    /** 执行成功，无错误 */
    OK,

    /** 播放列表正在执行中，防止重复提交 */
    PLAYLIST_LOCKED,

    /** 全局熔断打开，暂时拒绝新执行 */
    CIRCUIT_OPEN,

    /** 搜索结果为空或不足 */
    SEARCH_EMPTY,

    /** LLM 调用超时 */
    LLM_TIMEOUT,

    /** LLM 调用失败 */
    LLM_ERROR,

    /** 执行超时 */
    EXECUTION_TIMEOUT,

    /** 内部未知错误 */
    INTERNAL_ERROR
}
