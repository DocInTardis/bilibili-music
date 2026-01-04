package com.example.bilibilimusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 常见错误聚合统计
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorStats {

    /** 统计窗口内总错误数 */
    private long totalErrors;

    /** 搜索结果为空类错误 */
    private long emptySearchErrors;

    /** LLM 超时或调用失败类错误 */
    private long llmTimeoutErrors;

    /** 其他错误 */
    private long otherErrors;
}
