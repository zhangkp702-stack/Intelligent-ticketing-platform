package org.opengoofy.index12306.ai.agentservice.action.service;


import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录当前进程内哪些对话轮次刚刚成功创建了操作草案。
 *
 * <p>该信号只用于跳过普通问答的无意义数据库查询，不能替代数据库状态、确认令牌或用户授权。</p>
 */
@Component
public class ActionDraftCreationTracker {

    private final Set<String> createdTurnIds = ConcurrentHashMap.newKeySet();

    /**
     * 标记指定轮次已经通过草案工具成功持久化操作草案。
     *
     * @param turnId 创建草案的轮次标识
     */
    public void markCreated(String turnId) {
        // 仅在草案服务成功返回后写入信号，失败工具调用不会触发后续动作表查询。
        createdTurnIds.add(Objects.requireNonNull(turnId, "turnId"));
    }

    /**
     * 原子消费指定轮次的草案创建信号。
     *
     * @param turnId 当前轮次标识
     * @return 本轮是否成功创建过草案
     */
    public boolean consumeCreated(String turnId) {
        // 移除操作保证同一轮完成、异常或取消路径只处理一次信号，避免进程内残留。
        return createdTurnIds.remove(Objects.requireNonNull(turnId, "turnId"));
    }
}
