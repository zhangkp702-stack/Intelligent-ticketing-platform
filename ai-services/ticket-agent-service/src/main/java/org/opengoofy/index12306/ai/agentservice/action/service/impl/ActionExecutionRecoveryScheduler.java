package org.opengoofy.index12306.ai.agentservice.action.service.impl;

import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 周期恢复因进程宕机而遗留的排队记录和过期 Action 执行租约。
 */
@Service
@ConditionalOnProperty(
        prefix = "index12306.agent.action",
        name = "recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ActionExecutionRecoveryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionExecutionRecoveryScheduler.class);
    private final ActionStateService actionStateService;
    private final AtomicBoolean scanning = new AtomicBoolean();

    /**
     * 创建 Action 过期租约恢复器。
     *
     * @param actionStateService 操作事务状态服务
     */
    public ActionExecutionRecoveryScheduler(ActionStateService actionStateService) {
        this.actionStateService = actionStateService;
    }

    /**
     * 周期结束未领取的 QUEUED，并把租约过期的 STARTED 转入 UNKNOWN 对账。
     */
    @Scheduled(fixedDelayString = "${index12306.agent.action.recovery-interval-millis:5000}")
    public void recoverExpiredExecutions() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            // 多实例可同时扫描候选，最终由执行记录行锁和状态复检裁决。
            int recovered = actionStateService.recoverExpiredExecutions();
            if (recovered > 0) {
                LOGGER.warn("已恢复排队超时或租约过期的Agent写操作，count={}", recovered);
            }
        } finally {
            scanning.set(false);
        }
    }
}
