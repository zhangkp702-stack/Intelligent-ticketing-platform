-- 第二阶段：在票务库创建追加式 CDC Outbox 和外部结果消息 Inbox。
-- 使用方式：
--   1. 连接 MySQL 后执行：USE `12306_ticket`;
--   2. 再执行本脚本。
-- 本脚本不迁移、不删除旧 t_reliable_outbox_event 数据，可重复执行。

DROP PROCEDURE IF EXISTS `upgrade_ticket_cdc_outbox_and_inbox`;
DELIMITER $$
CREATE PROCEDURE `upgrade_ticket_cdc_outbox_and_inbox`()
BEGIN
    IF DATABASE() <> '12306_ticket' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '本脚本只能在 12306_ticket 数据库中执行';
    END IF;

    -- 只保存不可变事件事实；Canal ACK 位点负责发布进度，不再写 PENDING/PUBLISHING/PUBLISHED。
    CREATE TABLE IF NOT EXISTS `t_cdc_outbox_event`
    (
        `id`                bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
        `namespace`         varchar(64)  NOT NULL COMMENT '事件业务域',
        `event_id`          varchar(128) NOT NULL COMMENT '稳定事件标识',
        `event_type`        varchar(64)  NOT NULL COMMENT '稳定事件类型',
        `aggregate_id`      varchar(128) NOT NULL COMMENT '业务聚合标识',
        `deduplication_key` varchar(160) NOT NULL COMMENT '业务幂等键',
        `message_key`       varchar(160) NOT NULL COMMENT 'RocketMQ 消息键',
        `destination_topic` varchar(128) NOT NULL COMMENT '目标 Topic',
        `destination_tag`   varchar(64)  NOT NULL COMMENT '目标 Tag',
        `delay_level`       int unsigned NOT NULL DEFAULT 0 COMMENT 'RocketMQ 延迟级别，0 表示普通消息',
        `payload`           mediumtext   NOT NULL COMMENT '不可变消息载荷 JSON',
        `event_version`     bigint unsigned NOT NULL COMMENT '事件协议版本',
        `created_at`        datetime(3) NOT NULL COMMENT '事件创建时间',
        PRIMARY KEY (`id`),
        UNIQUE KEY `uk_cdc_outbox_event` (`namespace`, `event_id`),
        UNIQUE KEY `uk_cdc_outbox_dedupe` (`namespace`, `deduplication_key`),
        KEY `idx_cdc_outbox_created` (`created_at`, `id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
      COMMENT='票务 CDC 追加式事件表';

    -- 订单结果来自外部订单库，Inbox 只记录本地消费租约、围栏和失败状态。
    CREATE TABLE IF NOT EXISTS `t_reliable_inbox_consumption`
    (
        `namespace`        varchar(64)  NOT NULL COMMENT '事件业务域',
        `event_id`         varchar(128) NOT NULL COMMENT '生产者稳定事件标识',
        `consumer_name`    varchar(128) NOT NULL COMMENT '本地消费者名称',
        `event_version`    bigint unsigned NOT NULL COMMENT '事件协议版本',
        `status`           varchar(32)  NOT NULL COMMENT 'PROCESSING RETRY_WAIT SUCCEEDED FAILED',
        `attempt_count`    int unsigned NOT NULL DEFAULT 0 COMMENT '已领取次数',
        `max_attempts`     int unsigned NOT NULL COMMENT '最大领取次数',
        `next_retry_at`    datetime(3) DEFAULT NULL COMMENT '下一次允许领取时间',
        `lease_owner`      varchar(128) DEFAULT NULL COMMENT '当前消费租约所有者',
        `lease_until`      datetime(3) DEFAULT NULL COMMENT '当前消费租约到期时间',
        `fencing_token`    bigint unsigned NOT NULL DEFAULT 0 COMMENT '消费围栏令牌',
        `failure_category` varchar(64) DEFAULT NULL COMMENT '最近失败分类',
        `failure_message`  varchar(512) DEFAULT NULL COMMENT '最近失败摘要',
        `started_at`       datetime(3) DEFAULT NULL COMMENT '本次处理开始时间',
        `finished_at`      datetime(3) DEFAULT NULL COMMENT '成功或最终失败时间',
        `created_at`       datetime(3) NOT NULL COMMENT '首次接收时间',
        `updated_at`       datetime(3) NOT NULL COMMENT '最近状态更新时间',
        PRIMARY KEY (`namespace`, `event_id`, `consumer_name`),
        KEY `idx_reliable_inbox_retry` (`namespace`, `consumer_name`, `status`, `next_retry_at`),
        KEY `idx_reliable_inbox_lease` (`namespace`, `consumer_name`, `status`, `lease_until`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
      COMMENT='票务外部事件消费 Inbox';
END$$
DELIMITER ;

CALL `upgrade_ticket_cdc_outbox_and_inbox`();
DROP PROCEDURE `upgrade_ticket_cdc_outbox_and_inbox`;

-- 验收：应各返回一行；CDC Outbox 不应包含 status、lease、retry 或 published 字段。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('t_cdc_outbox_event', 't_reliable_inbox_consumption')
ORDER BY table_name;

SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 't_cdc_outbox_event'
ORDER BY ordinal_position;
