-- Canal Relay 第二阶段：创建失败事件重试表。
-- 本脚本只登记最终失败的单条事件；正常成功消息不写入该表。
-- 执行前请确认数据库账号具备创建数据库和表的权限，并先备份目标环境。

CREATE DATABASE IF NOT EXISTS `12306_canal_relay`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `12306_canal_relay`;

CREATE TABLE IF NOT EXISTS `t_canal_relay_retry`
(
    `id`                   bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `retry_key`            varchar(191) NOT NULL COMMENT '稳定事件级唯一键',
    `namespace`            varchar(64) NOT NULL COMMENT '事件业务域',
    `event_id`             varchar(128) NOT NULL COMMENT 'Outbox 事件标识',
    `command_id`           varchar(160) DEFAULT NULL COMMENT '订单创建命令标识',
    `aggregate_id`         varchar(128) NOT NULL COMMENT '业务聚合标识',
    `destination_topic`    varchar(128) NOT NULL COMMENT 'RocketMQ 目标 Topic',
    `destination_tag`      varchar(64) NOT NULL COMMENT 'RocketMQ 目标 Tag',
    `delay_level`          int unsigned NOT NULL DEFAULT 0 COMMENT 'RocketMQ 延迟级别',
    `payload_ciphertext`   mediumtext NOT NULL COMMENT 'AES-GCM 加密消息载荷',
    `payload_fingerprint`  char(64) NOT NULL COMMENT '明文载荷 SHA-256 摘要',
    `payload_key_version`  int unsigned NOT NULL COMMENT '载荷加密密钥版本',
    `status`               varchar(16) NOT NULL COMMENT 'RETRY 或 DEAD',
    `attempt_count`        int unsigned NOT NULL DEFAULT 0 COMMENT '已执行发送尝试次数',
    `max_attempts`         int unsigned NOT NULL COMMENT '允许自动发送的最大次数',
    `next_retry_time`      datetime(3) NOT NULL COMMENT '下一次允许重试时间',
    `last_error`           varchar(512) DEFAULT NULL COMMENT '限长且脱敏的失败摘要',
    `created_at`           datetime(3) NOT NULL COMMENT '首次登记时间',
    `updated_at`           datetime(3) NOT NULL COMMENT '最近修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_canal_relay_retry_key` (`retry_key`),
    KEY `idx_canal_relay_retry_status_time` (`status`, `next_retry_time`),
    KEY `idx_canal_relay_retry_aggregate` (`aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Canal Relay 单条失败事件重试任务';

-- 验收：唯一键和到期扫描索引应存在，载荷列不应为明文 payload。
SELECT index_name, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 't_canal_relay_retry'
  AND index_name IN ('uk_canal_relay_retry_key', 'idx_canal_relay_retry_status_time')
ORDER BY index_name, seq_in_index;
