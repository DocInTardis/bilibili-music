package com.example.bilibilimusic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("online_learning_sample")
public class OnlineLearningSample {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long userId;

    private Long playlistId;

    private String sessionId;

    private String traceId;

    private String executionId;

    private String nodeName;

    private String promptVersion;

    private String bvid;

    private String intentJson;

    private String featuresJson;

    private Integer baseScore;

    private Integer modelAdjustment;

    private Integer finalScore;

    private Boolean accepted;

    private String decisionSource;

    private String modelName;

    private String modelVersion;

    private String variant;

    private Integer label;

    private Double labelWeight;

    private String labelSource;

    private LocalDateTime labeledAt;

    private Boolean trained;

    private LocalDateTime trainedAt;

    private String trainedModelVersion;

    private LocalDateTime createdAt;
}

