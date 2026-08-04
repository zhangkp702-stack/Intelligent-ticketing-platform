package org.opengoofy.index12306.ai.agentservice.conversation.service.impl;

import org.opengoofy.index12306.ai.agentservice.chat.config.AgentTurnProperties;
import org.opengoofy.index12306.ai.agentservice.chat.security.TurnSubmissionTokenService;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.exception.TurnSubmissionException;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责会话、消息和问答轮次的一致性写入，并合并异步摘要目标。
 */
@Service
public class ConversationMemoryServiceImpl implements ConversationMemoryService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final TurnRepository turnRepository;
    private final Clock clock;
    private final SummaryTaskService summaryTaskService;
    private final AgentTurnProperties turnProperties;
    private final TurnSubmissionTokenService turnSubmissionTokenService;
    private final String executionOwner;

    /**
     * 创建会话记忆写入服务。
     *
     * @param conversationRepository 会话仓储
     * @param messageRepository 消息仓储
     * @param turnRepository 轮次仓储
     * @param clock 统一时钟
     * @param summaryTaskService 会话摘要任务服务
     * @param turnProperties 服务端轮次提交和执行租约配置
     * @param turnSubmissionTokenService 轮次提交令牌服务
     */
    public ConversationMemoryServiceImpl(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            TurnRepository turnRepository,
            Clock clock,
            SummaryTaskService summaryTaskService,
            AgentTurnProperties turnProperties,
            TurnSubmissionTokenService turnSubmissionTokenService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.turnRepository = turnRepository;
        this.clock = clock;
        this.summaryTaskService = summaryTaskService;
        this.turnProperties = turnProperties;
        this.turnSubmissionTokenService = turnSubmissionTokenService;
        // 每个服务实例使用独立执行者标识，租约记录不依赖客户端或线程名称。
        this.executionOwner = "agent-turn-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 为用户创建一个新的活动会话。
     *
     * @param userId 用户标识
     * @param title 会话标题
     * @return 已持久化会话
     */
    @Transactional
    @Override
    public ConversationEntity createConversation(String userId, String title) {
        requireText(userId, "用户标识不能为空");
        Instant now = clock.instant();

        // 会话本身不携带模型或票务业务状态，摘要由后续异步任务维护。
        ConversationEntity conversation = ConversationEntity.create(userId, title, now);
        conversationRepository.insert(conversation);
        return conversation;
    }

    /**
     * 预创建不包含用户消息的服务端轮次，并签发绑定用户和会话的提交令牌。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 可供前端保存并首次提交的轮次凭证
     */
    @Transactional
    @Override
    public PreparedTurn prepareTurn(String userId, String conversationId) {
        requireText(userId, "用户标识不能为空");
        requireText(conversationId, "会话标识不能为空");

        // 先锁定并校验会话，避免删除事务与 DRAFT 轮次插入交叉产生孤儿数据。
        requireLockedConversation(userId, conversationId);
        Instant now = clock.instant();
        TurnEntity turn = TurnEntity.prepare(
                conversationId, now.plus(turnProperties.submissionTtl()), now);
        turnRepository.insert(turn);

        // 使用数据库回读值签名，消除不同数据库对时间精度和时区映射的规范化差异。
        TurnEntity persistedTurn = Optional.ofNullable(turnRepository.selectById(turn.getId()))
                .orElseThrow(() -> new IllegalStateException("预创建轮次写入失败"));
        String token = turnSubmissionTokenService.issue(persistedTurn, userId);
        return new PreparedTurn(
                conversationId,
                persistedTurn.getId(),
                token,
                persistedTurn.getSubmissionExpiresAt());
    }

    /**
     * 幂等写入当前用户问题并创建等待回答的运行中轮次。
     *
     * @param command 用户问题写入命令
     * @return 新建或已存在的轮次和用户消息
     */
    @Transactional
    @Override
    public StartedTurn startTurn(StartTurnCommand command) {
        // 首次提交和重复提交使用相同校验入口，关键参数不得为空。
        validateStartCommand(command);
        String content = command.content().trim();
        String payloadHash = fingerprint(content);

        // 先读取轮次定位会话，但所有写路径统一按“会话 -> 轮次”顺序加锁以避免删除死锁。
        TurnEntity observedTurn = Optional.ofNullable(turnRepository.selectById(command.turnId()))
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        if (!observedTurn.getConversationId().equals(command.conversationId())) {
            throw new IllegalArgumentException("轮次不属于当前会话");
        }

        // 锁定会话并校验所有权后再锁轮次，并发提交只能有一个请求迁移 DRAFT 状态。
        ConversationEntity conversation = requireLockedConversation(
                command.userId(), command.conversationId());
        TurnEntity turn = turnRepository.findLockedById(command.turnId())
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        if (!turn.getConversationId().equals(conversation.getId())) {
            throw new IllegalArgumentException("轮次不属于当前会话");
        }
        if (turn.getStatus() != TurnStatus.DRAFT) {
            if (!turn.hasPayloadHash(payloadHash)) {
                throw new TurnSubmissionException(
                        TurnSubmissionException.Reason.PAYLOAD_MISMATCH,
                        "轮次标识已经绑定不同的用户问题");
            }
            Instant reclaimNow = clock.instant();
            if (turn.reclaim(
                    executionOwner,
                    reclaimNow.plus(turnProperties.executionLease()),
                    reclaimNow)) {
                // 过期轮次取得新的 fencing token，后续流水线从持久化任务检查点继续执行。
                turnRepository.updateById(turn);
                MessageEntity existingMessage = requireMessage(turn.getUserMessageId());
                return toStartedTurn(conversation, turn, existingMessage, true);
            }
            // 已提交轮次只复用原用户消息，后续由流水线按终态回放或拒绝重复执行。
            MessageEntity existingMessage = requireMessage(turn.getUserMessageId());
            return toStartedTurn(conversation, turn, existingMessage, false);
        }

        Instant now = clock.instant();
        validateDraftSubmission(turn, command.userId(), command.submissionToken(), now);

        // 已锁定会话，分配严格递增消息序号并在同一事务中绑定内容、消息和执行租约。
        long sequence = conversation.nextMessageSequence(now);
        MessageEntity userMessage = MessageEntity.create(
                command.conversationId(),
                sequence,
                MessageRole.USER,
                MessageType.TEXT,
                content,
                "text/plain",
                command.tokenCount(),
                turn.getId(),
                turn.getId(),
                now);
        turn.start(
                userMessage.getId(),
                command.username(),
                payloadHash,
                executionOwner,
                now.plus(turnProperties.executionLease()),
                now);
        userMessage.attachTurn(turn.getId(), now);
        messageRepository.insert(userMessage);
        turnRepository.updateById(turn);
        // 消息序号保存在会话行中，MyBatis-Plus 下需显式更新以保持下一轮递增。
        conversationRepository.updateById(conversation);
        return toStartedTurn(conversation, turn, userMessage, true);
    }

    /**
     * 追加最终助手回答并原子完成本轮问答。
     *
     * @param command 助手回答写入命令
     * @return 新建或已存在的助手消息
     */
    @Transactional
    @Override
    public MessageEntity completeTurn(CompleteTurnCommand command) {
        requireText(command.userId(), "用户标识不能为空");
        requireText(command.content(), "助手回答不能为空");

        // 先读取轮次定位并锁定会话，所有终态写入与删除事务保持相同加锁顺序。
        TurnEntity observedTurn = Optional.ofNullable(turnRepository.selectById(command.turnId()))
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        ConversationEntity conversation = requireLockedConversation(
                command.userId(), observedTurn.getConversationId());
        TurnEntity turn = turnRepository.findLockedById(command.turnId())
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));

        // 已完成轮次直接返回原助手消息，保证客户端确认重试不重复写消息。
        if (turn.getStatus() == TurnStatus.COMPLETED) {
            return requireMessage(turn.getAssistantMessageId());
        }

        // 助手消息与轮次完成状态在同一事务提交，失败时一起回滚。
        Instant now = clock.instant();
        long sequence = conversation.nextMessageSequence(now);
        MessageEntity assistantMessage = MessageEntity.create(
                turn.getConversationId(),
                sequence,
                MessageRole.ASSISTANT,
                MessageType.TEXT,
                command.content(),
                "text/plain",
                command.tokenCount(),
                turn.getRequestId(),
                turn.getRequestId() + ":assistant",
                now);
        assistantMessage.attachTurn(turn.getId(), now);
        messageRepository.insert(assistantMessage);
        turn.complete(
                assistantMessage.getId(),
                command.executionOwner(),
                command.fencingToken(),
                now);
        // 助手消息、轮次终态和会话序号必须在同一事务内显式写回。
        turnRepository.updateById(turn);
        conversationRepository.updateById(conversation);
        // 与回答在同一事务内仅合并任务目标，MQ 发布和模型摘要在事务提交后异步执行。
        summaryTaskService.requestIfNeeded(turn.getConversationId(), sequence);
        return assistantMessage;
    }

    /**
     * 在没有生成最终助手消息时记录轮次失败。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param executionOwner 执行实例标识
     * @param fencingToken 领取时获得的 fencing token
     * @param failureCategory 稳定失败分类
     */
    @Transactional
    @Override
    public void failTurn(
            String userId,
            String turnId,
            String executionOwner,
            long fencingToken,
            String failureCategory) {
        // 失败收口与完成、取消、会话删除统一使用“会话 -> 轮次”数据库锁顺序。
        TurnEntity observedTurn = Optional.ofNullable(turnRepository.selectById(turnId))
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        requireLockedConversation(userId, observedTurn.getConversationId());
        TurnEntity turn = turnRepository.findLockedById(turnId)
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));

        // 只允许当前运行轮次进入失败终态，实体状态机拒绝覆盖其他终态。
        turn.fail(failureCategory, executionOwner, fencingToken, clock.instant());
        turnRepository.updateById(turn);
    }

    /**
     * 为当前执行实例续租运行中轮次，并在取消或接管后拒绝旧 token。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param executionOwner 执行实例标识
     * @param fencingToken 当前 fencing token
     * @return 成功续租时返回 true
     */
    @Transactional
    @Override
    public boolean heartbeatTurn(
            String userId,
            String turnId,
            String executionOwner,
            long fencingToken) {
        // 心跳与删除事务保持“会话 -> 轮次”锁顺序，避免恢复线程引入新的死锁环。
        TurnEntity observedTurn = Optional.ofNullable(turnRepository.selectById(turnId)).orElse(null);
        if (observedTurn == null) {
            return false;
        }
        requireLockedConversation(userId, observedTurn.getConversationId());
        TurnEntity turn = turnRepository.findLockedById(turnId).orElse(null);
        if (turn == null) {
            return false;
        }
        Instant now = clock.instant();
        if (!turn.heartbeat(
                executionOwner,
                fencingToken,
                now.plus(turnProperties.executionLease()),
                now)) {
            return false;
        }
        // 续租先持久化再返回成功，数据库仍是跨实例执行权的唯一真相。
        turnRepository.updateById(turn);
        return true;
    }

    /**
     * 读取幂等轮次的当前状态和已完成回答，用于决定是否可以安全复用结果。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @return 轮次状态和已完成回答
     */
    @Transactional(readOnly = true)
    @Override
    public TurnState getTurnState(String userId, String turnId) {
        TurnEntity turn = Optional.ofNullable(turnRepository.selectById(turnId))
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));

        // 先校验会话所有权，再按终态读取助手消息，避免幂等查询越权。
        requireConversation(userId, turn.getConversationId());
        String assistantContent = turn.getAssistantMessageId() == null
                ? null : requireMessage(turn.getAssistantMessageId()).getContent();
        return new TurnState(
                turn.getId(),
                turn.getConversationId(),
                turn.getStatus(),
                assistantContent,
                turn.getFailureCategory(),
                turn.getStartedAt(),
                turn.getFinishedAt());
    }

    /**
     * 将客户端已中止且仍在运行的轮次标记为取消。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     */
    @Transactional
    @Override
    public boolean cancelTurn(String userId, String turnId) {
        // 取消与完成、失败、会话删除统一按“会话 -> 轮次”顺序获取数据库锁。
        TurnEntity observedTurn = Optional.ofNullable(turnRepository.selectById(turnId))
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        requireLockedConversation(userId, observedTurn.getConversationId());
        TurnEntity turn = turnRepository.findLockedById(turnId)
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));

        // 终态轮次保持原结果，避免取消回调覆盖已经持久化的完成或失败状态。
        if (turn.getStatus() != TurnStatus.RUNNING) {
            return false;
        }
        // 使用轮次行锁与正常完成流程互斥，只允许运行状态迁移一次。
        turn.cancel(clock.instant());
        turnRepository.updateById(turn);
        return true;
    }

    /**
     * 仅允许仍持有相同执行者和 fencing token 的流水线取消轮次。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param executionOwner 执行实例标识
     * @param fencingToken 当前 fencing token
     * @return 当前执行者成功取消时返回 true
     */
    @Transactional
    @Override
    public boolean cancelOwnedTurn(
            String userId,
            String turnId,
            String executionOwner,
            long fencingToken) {
        // 仍按“会话 -> 轮次”加锁，避免旧连接断开时与新执行者接管交叉覆盖。
        TurnEntity observedTurn = Optional.ofNullable(turnRepository.selectById(turnId)).orElse(null);
        if (observedTurn == null) {
            return false;
        }
        requireLockedConversation(userId, observedTurn.getConversationId());
        TurnEntity turn = turnRepository.findLockedById(turnId).orElse(null);
        if (turn == null || !turn.isOwnedBy(executionOwner, fencingToken)) {
            return false;
        }
        // 只有数据库确认执行权仍有效，客户端断流才有权结束该轮次。
        turn.cancel(clock.instant());
        turnRepository.updateById(turn);
        return true;
    }

    /**
     * 读取租约已过期轮次的恢复快照，真正接管仍由 startTurn 的行锁和 fencing token 决定。
     *
     * @return 可供后台恢复器尝试的轮次列表
     */
    @Transactional(readOnly = true)
    @Override
    public List<ExpiredTurnCandidate> findExpiredTurnCandidates() {
        // 候选扫描不持有写锁，多实例可以同时发现，后续只有一个实例能在行锁内接管。
        return turnRepository.findExpiredRunningTurns(TurnStatus.RUNNING, clock.instant(), 100).stream()
                .map(turn -> {
                    ConversationEntity conversation = conversationRepository.selectById(turn.getConversationId());
                    MessageEntity message = turn.getUserMessageId() == null
                            ? null : messageRepository.selectById(turn.getUserMessageId());
                    if (conversation == null || message == null || !StringUtils.hasText(turn.getUsername())) {
                        return null;
                    }
                    // 恢复只使用首次提交时已经固化的身份、会话和问题，不接受新的客户端字段。
                    return new ExpiredTurnCandidate(
                            conversation.getUserId(),
                            turn.getUsername(),
                            turn.getConversationId(),
                            turn.getId(),
                            message.getContent());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 锁定并校验会话所有权。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 锁定的会话实体
     */
    private ConversationEntity requireLockedConversation(String userId, String conversationId) {
        ConversationEntity conversation = conversationRepository.findLockedById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        assertOwner(conversation, userId);
        return conversation;
    }

    /**
     * 读取并校验会话所有权。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 会话实体
     */
    private ConversationEntity requireConversation(String userId, String conversationId) {
        ConversationEntity conversation = Optional.ofNullable(conversationRepository.selectById(conversationId))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        assertOwner(conversation, userId);
        return conversation;
    }

    /**
     * 校验会话属于当前用户。
     *
     * @param conversation 会话实体
     * @param userId 当前用户标识
     */
    private void assertOwner(ConversationEntity conversation, String userId) {
        requireText(userId, "用户标识不能为空");
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
    }

    /**
     * 读取必须存在的消息。
     *
     * @param messageId 消息标识
     * @return 消息实体
     */
    private MessageEntity requireMessage(String messageId) {
        return Optional.ofNullable(messageRepository.selectById(messageId))
                .orElseThrow(() -> new IllegalStateException("轮次关联消息不存在"));
    }

    /**
     * 校验用户问题命令的必填字段。
     *
     * @param command 用户问题命令
     */
    private void validateStartCommand(StartTurnCommand command) {
        Objects.requireNonNull(command, "command");
        requireText(command.userId(), "用户标识不能为空");
        requireText(command.conversationId(), "会话标识不能为空");
        requireText(command.turnId(), "轮次标识不能为空");
        requireText(command.submissionToken(), "轮次提交令牌不能为空");
        requireText(command.username(), "用户名不能为空");
        requireText(command.content(), "用户问题不能为空");
    }

    /**
     * 校验 DRAFT 轮次提交令牌和首次提交截止时间。
     *
     * @param turn 待提交轮次
     * @param userId 当前认证用户
     * @param submissionToken 客户端回传令牌
     * @param now 当前时间
     */
    private void validateDraftSubmission(
            TurnEntity turn,
            String userId,
            String submissionToken,
            Instant now) {
        if (!now.isBefore(turn.getSubmissionExpiresAt())) {
            throw new TurnSubmissionException(
                    TurnSubmissionException.Reason.SUBMISSION_EXPIRED,
                    "轮次首次提交时间窗口已经结束");
        }
        // HMAC 必须覆盖当前用户、会话、轮次和过期时间，禁止伪造或跨轮次复用。
        if (!turnSubmissionTokenService.matches(turn, userId, submissionToken)) {
            throw new TurnSubmissionException(
                    TurnSubmissionException.Reason.INVALID_TOKEN,
                    "轮次提交令牌无效");
        }
    }

    /**
     * 计算标准化用户问题的稳定 SHA-256 指纹。
     *
     * @param content 已去除首尾空白的用户问题
     * @return 小写十六进制内容指纹
     */
    private String fingerprint(String content) {
        try {
            // 使用 JDK 标准摘要算法，避免内容指纹依赖平台默认字符集。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 校验文本字段不为空。
     *
     * @param value 字段值
     * @param message 失败说明
     */
    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 转换轮次和用户消息为稳定返回结果。
     *
     * @param conversation 会话实体
     * @param turn 轮次实体
     * @param message 用户消息实体
     * @param created 是否本次新建
     * @return 启动轮次结果
     */
    private StartedTurn toStartedTurn(
            ConversationEntity conversation,
            TurnEntity turn,
            MessageEntity message,
            boolean created) {
        return new StartedTurn(
                conversation.getId(), turn.getId(), message.getId(),
                message.getSequenceNo(), created, turn.getLeaseOwner(), turn.getFencingToken());
    }

}
