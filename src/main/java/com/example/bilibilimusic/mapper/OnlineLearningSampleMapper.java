package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.OnlineLearningSample;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OnlineLearningSampleMapper extends BaseMapper<OnlineLearningSample> {

    @Update("""
        UPDATE online_learning_sample s
        JOIN (
          SELECT id FROM online_learning_sample
          WHERE conversation_id = #{conversationId}
            AND bvid = #{bvid}
            AND label IS NULL
          ORDER BY created_at DESC
          LIMIT 1
        ) t ON s.id = t.id
        SET s.label = #{label},
            s.label_weight = #{weight},
            s.label_source = #{source},
            s.labeled_at = NOW(),
            s.trained = FALSE,
            s.trained_at = NULL,
            s.trained_model_version = NULL
        """)
    int labelLatestUnlabeled(@Param("conversationId") Long conversationId,
                             @Param("bvid") String bvid,
                             @Param("label") Integer label,
                             @Param("weight") Double weight,
                             @Param("source") String source);

    @Select("""
        SELECT * FROM online_learning_sample
        WHERE label IS NOT NULL
          AND (trained IS NULL OR trained = FALSE)
        ORDER BY labeled_at ASC, id ASC
        LIMIT #{limit}
        """)
    List<OnlineLearningSample> listLabeledNotTrained(@Param("limit") int limit);

    @Update("""
        UPDATE online_learning_sample
        SET trained = TRUE,
            trained_at = NOW(),
            trained_model_version = #{modelVersion}
        WHERE id = #{id}
        """)
    int markTrained(@Param("id") Long id, @Param("modelVersion") String modelVersion);

    @Select("""
        SELECT
          COUNT(*) AS total,
          SUM(CASE WHEN label IS NOT NULL THEN 1 ELSE 0 END) AS labeled,
          SUM(CASE WHEN trained = TRUE THEN 1 ELSE 0 END) AS trained,
          SUM(CASE WHEN label IS NOT NULL AND accepted = TRUE AND label = 1 THEN 1 ELSE 0 END) AS tp,
          SUM(CASE WHEN label IS NOT NULL AND accepted = TRUE AND label = 0 THEN 1 ELSE 0 END) AS fp,
          SUM(CASE WHEN label IS NOT NULL AND accepted = FALSE AND label = 1 THEN 1 ELSE 0 END) AS fn,
          SUM(CASE WHEN label IS NOT NULL AND accepted = FALSE AND label = 0 THEN 1 ELSE 0 END) AS tn
        FROM online_learning_sample
        """)
    java.util.Map<String, Object> aggregate();
}
