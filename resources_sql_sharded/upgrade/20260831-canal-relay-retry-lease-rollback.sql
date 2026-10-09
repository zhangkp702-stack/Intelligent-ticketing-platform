-- Canal Relay 第四阶段回滚：移除失败任务租约字段和扫描索引。

USE `12306_canal_relay`;

ALTER TABLE `t_canal_relay_retry`
    DROP KEY `idx_canal_relay_retry_lease`,
    ADD KEY `idx_canal_relay_retry_status_time` (`status`, `next_retry_time`),
    DROP COLUMN `lease_until`,
    DROP COLUMN `lease_owner`;