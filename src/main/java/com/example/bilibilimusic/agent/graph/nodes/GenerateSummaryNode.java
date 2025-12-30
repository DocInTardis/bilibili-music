package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.skill.SummarySkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 生成总结节点（终止节点）
 */
@Slf4j
@RequiredArgsConstructor
public class GenerateSummaryNode implements AgentNode {
    
    private final SummarySkill summarySkill;
    private final SimpMessagingTemplate messagingTemplate;
    
    @Override
    public NodeResult execute(PlaylistContext state) {
        log.info("[SummaryNode] 开始生成播放列表总结");
        
        state.setCurrentStage(PlaylistContext.Stage.SUMMARY_GENERATION);
        
        // 调用SummarySkill生成总结
        summarySkill.execute(state);
        
        // 推送最终总结
        pushFinalSummary(state);
        
        log.info("[SummaryNode] 总结生成完成");
        return NodeResult.success(null); // null表示结束
    }
    
    private void pushFinalSummary(PlaylistContext context) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("summary", context.getSummary());
        payload.put("selectedCount", context.getSelectedVideos().size());
        payload.put("rejectedCount", context.getRejectedVideos().size());
        
        // 选中的视频列表
        var selectedList = new java.util.ArrayList<Map<String, String>>();
        for (var v : context.getSelectedVideos()) {
            Map<String, String> item = new HashMap<>();
            item.put("bvid", v.getBvid());
            item.put("title", v.getTitle());
            item.put("author", v.getAuthor());
            item.put("duration", v.getDuration());
            selectedList.add(item);
        }
        payload.put("selectedVideos", selectedList);

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("final_summary")
            .stage("SUMMARY_GENERATION")
            .content("✨ 播放列表生成完成")
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }
}
