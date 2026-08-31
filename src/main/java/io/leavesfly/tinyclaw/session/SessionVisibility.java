package io.leavesfly.tinyclaw.session;

/**
 * 会话可见性。
 *
 * <p>刻意只有两档。这里要解决的问题是"Web 控制台一个密码进来就能看到所有人的会话"，
 * 属于协作可见性控制，不是多租户隔离——把它做成细粒度 ACL 只会引入一套没人维护的权限模型，
 * 却仍然挡不住持有 Gateway 凭据的人。</p>
 */
public enum SessionVisibility {

    /** 仅 owner 与显式成员可见。通道私聊产生的会话默认落在这一档 */
    PRIVATE,

    /** 所有能访问 Gateway 的人可见。群聊会话默认落在这一档 */
    SHARED
}
