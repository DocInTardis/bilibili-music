package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.context.PlaylistContext;

/**
 * Agent 节点接口
 * 
 * 每个节点代表状态机中的一个状态转换
 * 节点的职责：
 * 1. 只读取 State
 * 2. 只修改 State
 * 3. 不决定下一步走哪（由条件边决定）
 */
@FunctionalInterface
public interface AgentNode {
    
    /**
     * 执行节点逻辑
     * 
     * @param state 当前状态（PlaylistContext）
     * @return 执行结果（用于条件边判断）
     */
    NodeResult execute(PlaylistContext state);
    
    /**
     * 节点执行结果
     */
    class NodeResult {
        private final boolean success;
        private final String nextNode; // 建议的下一个节点（可选）
        private final Object data;     // 附加数据（可选）
        
        private NodeResult(boolean success, String nextNode, Object data) {
            this.success = success;
            this.nextNode = nextNode;
            this.data = data;
        }
        
        public static NodeResult success() {
            return new NodeResult(true, null, null);
        }
        
        public static NodeResult success(String nextNode) {
            return new NodeResult(true, nextNode, null);
        }
        
        public static NodeResult success(String nextNode, Object data) {
            return new NodeResult(true, nextNode, data);
        }
        
        public static NodeResult failure() {
            return new NodeResult(false, null, null);
        }
        
        public static NodeResult failure(String reason) {
            return new NodeResult(false, null, reason);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getNextNode() {
            return nextNode;
        }
        
        public Object getData() {
            return data;
        }
    }
}
