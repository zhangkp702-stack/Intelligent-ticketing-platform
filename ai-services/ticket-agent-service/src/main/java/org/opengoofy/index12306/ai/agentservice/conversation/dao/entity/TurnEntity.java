package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 从一条用户问题到最终助手回答的完整执行轮次。
 */
@Getter
@TableName("t_agent_turn")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TurnEntity extends AgentBaseEntity {

    /**
     * 轮次所属的会话标识。
     */
    private String conversationId;

    /**
     * 服务端生成的内部请求标识，与轮次主键保持一致。
     */
    private String requestId;

    /**
     * 下游业务归属查询使用的用户名。
     */
    private String username;

    /**
     * 本轮用户消息的标识。
     */
    private String userMessageId;

    /**
     * 本轮最终助手消息的标识。
     */
    private String assistantMessageId;

    /**
     * 轮次当前执行状态。
     */
    private TurnStatus status;

    /**
     * 首次提交问题内容的哈希值，用于识别重复或冲突提交。
     */
    private String payloadHash;

    /**
     * 预创建轮次允许首次提交的截止时间。
     */
    private Instant submissionExpiresAt;

    /**
     * 当前持有轮次执行租约的服务实例标识。
     */
    private String leaseOwner;

    /**
     * 当前轮次执行租约的截止时间。
     */
    private Instant leaseUntil;

    /**
     * 轮次执行权每次领取或接管时递增的隔离令牌。
     */
    private long fencingToken;

    /**
     * 本轮已分配的最大 SSE 事件序号。
     */
    private long lastEventSequence;

    /**
     * 当前执行者最近一次成功续租的时间。
     */
    private Instant lastHeartbeatAt;

    /**
     * 轮次首次进入运行状态的时间。
     */
    private Instant startedAt;

    /**
     * 轮次进入终态的时间。
     */
    private Instant finishedAt;

    /**
     * 轮次失败时记录的稳定失败分类。
     */
    private String failureCategory;

    private TurnEntity(String conversationId, Instant submissionExpiresAt, Instant now) {
        super(now);
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        // 内部请求标识沿用服务端轮次主键，旧审计字段不再接收客户端业务 ID。
        this.requestId = getId();
        this.submissionExpiresAt = Objects.requireNonNull(submissionExpiresAt, "submissionExpiresAt");
        this.status = TurnStatus.DRAFT;
    }

    /**
     * 预创建尚未绑定用户问题的服务端轮次。
     *
     * @param conversationId 会话标识
     * @param submissionExpiresAt 首次提交截止时间
     * @param now 创建时间
     * @return 待提交轮次实体
     */
    public static TurnEntity prepare(String conversationId, Instant submissionExpiresAt, Instant now) {
        // DRAFT 不包含用户消息，也不会触发模型或真实业务操作。
        return new TurnEntity(conversationId, submissionExpiresAt, now);
    }

    /**
     * 在持有当前 Turn 数据库行锁时分配下一个 SSE 事件序号。
     *
     * @param now 序号分配时间
     * @return 清理历史事件后仍严格递增的新序号
     */
    public long nextEventSequence(Instant now) {
        // 水位保存在 Turn 主记录中，不能根据可能已被清理的事件表重新计算。
        this.lastEventSequence++;
        touch(now);
        return this.lastEventSequence;
    }

    /**
     * 首次绑定用户问题并领取当前轮次的在线执行权。
     *
     * @param userMessageId 已持久化的用户消息标识
     * @param username 下游用户归属查询所需用户名
     * @param payloadHash 不可变问题内容指纹
     * @param owner 当前服务实例执行者标识
     * @param leaseUntil 执行租约截止时间
     * @param now 开始执行时间
     */
    public void start(
            String userMessageId,
            String username,
            String payloadHash,
            String owner,
            Instant leaseUntil,
            Instant now) {
        if (status != TurnStatus.DRAFT) {
            throw new IllegalStateException("轮次已经提交");
        }
        // 内容、执行者和 fencing token 在同一次状态迁移中固化，避免并发请求同时执行。
        this.userMessageId = Objects.requireNonNull(userMessageId, "userMessageId");
        this.username = Objects.requireNonNull(username, "username");
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash");
        this.leaseOwner = Objects.requireNonNull(owner, "owner");
        this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        this.fencingToken += 1L;
        this.lastHeartbeatAt = now;
        this.status = TurnStatus.RUNNING;
        this.startedAt = now;
        touch(now);
    }

    /**
     * 在原执行租约过期后由新实例接管运行中轮次。
     *
     * @param owner 新执行实例标识
     * @param leaseUntil 新租约截止时间
     * @param now 当前时间
     * @return 成功接管时返回 true
     */
    public boolean reclaim(String owner, Instant leaseUntil, Instant now) {
        if (status != TurnStatus.RUNNING
                || this.leaseUntil == null
                || now.isBefore(this.leaseUntil)) {
            return false;
        }
        // 接管只更换执行权，不改变已经固化的问题、消息和服务端任务计划。
        this.leaseOwner = Objects.requireNonNull(owner, "owner");
        this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        this.fencingToken++;
        this.lastHeartbeatAt = now;
        touch(now);
        return true;
    }

    /**
     * 为当前合法执行者续租，取消或接管后旧执行者续租会失败。
     *
     * @param owner 执行实例标识
     * @param expectedFencingToken 预期 fencing token
     * @param renewedLeaseUntil 新租约截止时间
     * @param now 当前时间
     * @return 当前执行权仍有效并完成续租时返回 true
     */
    public boolean heartbeat(
            String owner,
            long expectedFencingToken,
            Instant renewedLeaseUntil,
            Instant now) {
        if (!isOwnedBy(owner, expectedFencingToken)) {
            return false;
        }
        // 心跳只延长当前 token 的租约，不能改变 fencing token 或重新激活终态轮次。
        this.leaseUntil = Objects.requireNonNull(renewedLeaseUntil, "renewedLeaseUntil");
        this.lastHeartbeatAt = now;
        touch(now);
        return true;
    }

    /**
     * 判断执行实例和 fencing token 是否仍拥有当前运行轮次。
     *
     * @param owner 执行实例标识
     * @param expectedFencingToken 预期 fencing token
     * @return 当前执行权完全匹配时返回 true
     */
    public boolean isOwnedBy(String owner, long expectedFencingToken) {
        return status == TurnStatus.RUNNING
                && Objects.equals(leaseOwner, owner)
                && fencingToken == expectedFencingToken;
    }

    /**
     * 判断已提交轮次是否绑定相同问题内容。
     *
     * @param candidateHash 当前请求内容指纹
     * @return 指纹与首次提交完全一致时返回 true
     */
    public boolean hasPayloadHash(String candidateHash) {
        // DRAFT 尚未绑定指纹，只有已经提交的轮次允许参与重复请求比较。
        return payloadHash != null && payloadHash.equals(candidateHash);
    }

    /**
     * 使用最终助手消息完成本轮问答。
     *
     * @param messageId 助手消息标识
     * @param owner 执行实例标识
     * @param expectedFencingToken 领取时获得的 fencing token
     * @param now 完成时间
     */
    public void complete(
            String messageId,
            String owner,
            long expectedFencingToken,
            Instant now) {
        requireOwner(owner, expectedFencingToken);
        // 完成状态与助手消息引用在同一事务中更新，避免出现无回答的完成轮次。
        this.assistantMessageId = Objects.requireNonNull(messageId, "messageId");
        this.status = TurnStatus.COMPLETED;
        this.finishedAt = now;
        clearLease();
        touch(now);
    }

    /**
     * 记录本轮在生成最终回答前失败。
     *
     * @param category 稳定失败分类
     * @param owner 执行实例标识
     * @param expectedFencingToken 领取时获得的 fencing token
     * @param now 失败时间
     */
    public void fail(
            String category,
            String owner,
            long expectedFencingToken,
            Instant now) {
        requireOwner(owner, expectedFencingToken);
        // 只保存稳定分类，不把可能含敏感正文的异常消息写入轮次表。
        this.failureCategory = category;
        this.status = TurnStatus.FAILED;
        this.finishedAt = now;
        clearLease();
        touch(now);
    }

    /**
     * 在客户端主动断开流式连接时取消仍在运行的轮次。
     *
     * @param now 取消时间
     */
    public void cancel(Instant now) {
        if (status != TurnStatus.RUNNING) {
            throw new IllegalStateException("轮次已经结束");
        }
        // 取消不记录外部异常正文，仅通过终态区分客户端中止和服务失败。
        this.status = TurnStatus.CANCELLED;
        this.finishedAt = now;
        clearLease();
        touch(now);
    }

    /**
     * 清理终态轮次不再需要的在线执行租约。
     */
    private void clearLease() {
        // fencing token 保留用于审计，终态仅清除当前执行者和租约时间。
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.lastHeartbeatAt = null;
    }

    /**
     * 校验终态写入仍由当前 fencing token 对应的执行者提交。
     *
     * @param owner 执行实例标识
     * @param expectedFencingToken 预期 fencing token
     */
    private void requireOwner(String owner, long expectedFencingToken) {
        if (!isOwnedBy(owner, expectedFencingToken)) {
            throw new IllegalStateException("轮次执行权已经失效");
        }
    }
}
