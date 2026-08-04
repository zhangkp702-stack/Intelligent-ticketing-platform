package org.opengoofy.index12306.ai.agentservice.chat.stream.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.EventType;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.AgentBaseEntity;

import java.time.Instant;
import java.util.Objects;

/**
 * 保存单个 Turn 已经对外发布的可重放 SSE 事件。
 */
@Getter
@TableName("t_agent_stream_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StreamEventEntity extends AgentBaseEntity {

    /**
     * SSE 事件所属的服务端问答轮次标识。
     */
    private String turnId;

    /**
     * 事件在当前轮次内严格递增的序号。
     */
    private long eventSequence;

    /**
     * 对外发布的 SSE 事件类型。
     */
    private EventType eventType;

    /**
     * 可直接重放给客户端的完整事件 JSON。
     */
    private String payloadJson;

    /**
     * 是否为结束当前流的终态事件。
     */
    private boolean terminal;

    private StreamEventEntity(
            String turnId,
            long eventSequence,
            EventType eventType,
            String payloadJson,
            boolean terminal,
            Instant now) {
        super(now);
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.eventSequence = eventSequence;
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
        this.terminal = terminal;
    }

    /**
     * 创建已经分配单调序号的持久化流事件。
     *
     * @param turnId 所属服务端轮次
     * @param eventSequence 轮次内严格递增的事件序号
     * @param eventType SSE 事件类型
     * @param payloadJson 完整事件 JSON
     * @param terminal 是否为 DONE 或 ERROR 终态
     * @param now 创建时间
     * @return 可直接写入数据库的事件实体
     */
    public static StreamEventEntity create(
            String turnId,
            long eventSequence,
            EventType eventType,
            String payloadJson,
            boolean terminal,
            Instant now) {
        // 序号从 1 开始，0 专门表示客户端尚未收到任何事件。
        if (eventSequence <= 0L) {
            throw new IllegalArgumentException("事件序号必须大于零");
        }
        return new StreamEventEntity(
                turnId, eventSequence, eventType, payloadJson, terminal, now);
    }
}
