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
@TableName("online_learning_model")
public class OnlineLearningModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelName;

    private String modelVersion;

    private String weightsJson;

    private Integer trainedSamples;

    private String metricsJson;

    private LocalDateTime createdAt;
}

