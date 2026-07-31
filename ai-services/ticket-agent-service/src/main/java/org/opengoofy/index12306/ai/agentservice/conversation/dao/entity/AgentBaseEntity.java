package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 智能体持久化实体统一的标识、乐观锁和审计时间字段。
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AgentBaseEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @Version
    @TableField("version")
    private long version;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
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
