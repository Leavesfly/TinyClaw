package io.leavesfly.tinyclaw.collaboration;

/**
 * Human-in-the-Loop 审批回调接口
 * <p>在协同流程的关键节点（如 Workflow 节点执行前、分层决策的最终决策层等），
 * 暂停执行并等待人类审批。
 *
 * <p>使用示例：
 * <pre>{@code
 * CollaborationConfig config = CollaborationConfig.workflow("部署方案", workflow)
 *         .withApproval((ctx) -> {
 *             // 通过 IM 通道发送审批请求，等待用户回复
 *             System.out.println("需要审批: " + ctx.getDescription());
 *             return new ApprovalResult(true, "已批准");
 *         });
 * }</pre>
 */
@FunctionalInterface
public interface ApprovalCallback {

    /**
     * 请求人类审批
     *
     * @param context 审批上下文，包含待审批的内容描述和相关信息
     * @return 审批结果
     */
    ApprovalResult requestApproval(ApprovalContext context);

    /**
     * 审批上下文
     */
    class ApprovalContext {
        /** 审批节点标识（如 Workflow 节点 ID） */
        private final String nodeId;
        /** 审批描述（人类可读的审批内容说明） */
        private final String description;
        /** 待审批的内容（如 Agent 的决策结果） */
        private final String content;
        /** 关联的共享上下文 */
        private final SharedContext sharedContext;

        public ApprovalContext(String nodeId, String description, String content,
                               SharedContext sharedContext) {
            this.nodeId = nodeId;
            this.description = description;
            this.content = content;
            this.sharedContext = sharedContext;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getDescription() {
            return description;
        }

        public String getContent() {
            return content;
        }

        public SharedContext getSharedContext() {
            return sharedContext;
        }
    }

    /**
     * 审批结果
     */
    class ApprovalResult {
        /** 是否批准 */
        private final boolean approved;
        /** 审批意见（批准或拒绝的理由） */
        private final String feedback;

        public ApprovalResult(boolean approved, String feedback) {
            this.approved = approved;
            this.feedback = feedback;
        }

        /**
         * 快速创建批准结果
         */
        public static ApprovalResult approve(String feedback) {
            return new ApprovalResult(true, feedback);
        }

        /**
         * 快速创建拒绝结果
         */
        public static ApprovalResult reject(String feedback) {
            return new ApprovalResult(false, feedback);
        }

        public boolean isApproved() {
            return approved;
        }

        public String getFeedback() {
            return feedback;
        }
    }
}
