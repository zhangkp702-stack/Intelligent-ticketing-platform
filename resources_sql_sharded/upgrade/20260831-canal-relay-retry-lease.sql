-- Canal Relay 第四阶段：为失败任务增加可过期租约。
-- 租约由数据库条件更新原子抢占，进程崩溃后由 lease_until 到期恢复。

USE `12306_canal_relay`;

ALTER TABLE `t_canal_relay_retry`
    ADD COLUMN `lease_owner` varchar(64) DEFAULT NULL COMMENT '当前租约持有者' AFTER `last_error`,
    ADD COLUMN `lease_until` datetime(3) DEFAULT NULL COMMENT '租约过期时间' AFTER `lease_owner`,
    DROP KEY `idx_canal_relay_retry_status_time`,
    ADD KEY `idx_canal_relay_retry_lease` (`status`, `next_retry_time`, `lease_until`);

-- 验收：租约字段和扫描索引应存在，且不会改变载荷密文存储约束。
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 't_canal_relay_retry'
  AND column_name IN ('lease_owner', 'lease_until');
