package org.opengoofy.index12306.ai.agentservice.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 智能体持久化实体统一的标识、乐观锁和审计时间字段。
 */
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AgentBaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 32)
    private String id;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 使用新的随机标识初始化实体审计字段。
     *
     * @param now 创建时间
     */
    protected AgentBaseEntity(Instant now) {
        // UUID 去除分隔符后固定为 32 位，避免依赖旧业务线的分布式 ID 组件。
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 在实体状态发生业务变化时刷新修改时间。
     *
     * @param now 修改时间
     */
    protected void touch(Instant now) {
        this.updatedAt = now;
    }
}
