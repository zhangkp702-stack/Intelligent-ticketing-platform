-- 为 ticket-service 可靠异步建单创建本地事务 Outbox。
-- 发布顺序：先执行本脚本，再发布返回 reservationId 的票务服务。
USE 12306_ticket;

CREATE TABLE IF NOT EXISTS `t_reliable_outbox_event`
(
    `namespace`                 varchar(64)  NOT NULL COMMENT '事件业务域',
    `event_id`                  varchar(128) NOT NULL COMMENT '服务端稳定事件标识',
    `deduplication_key`         varchar(128) NOT NULL COMMENT '业务去重键',
    `event_type`                varchar(64)  NOT NULL COMMENT '稳定事件类型',
    `aggregate_id`              varchar(128) NOT NULL COMMENT '业务聚合标识',
    `payload`                   text         NOT NULL COMMENT '不可变安全载荷',
    `event_version`             bigint       NOT NULL COMMENT '事件协议版本',
    `status`                    varchar(32)  NOT NULL COMMENT 'PENDING PUBLISHING PUBLISHED',
    `next_publish_at`           datetime(3)  NOT NULL COMMENT '最早发布时间',
    `publish_owner`             varchar(128) DEFAULT NULL COMMENT '发布租约实例',
    `publish_lease_until`       datetime(3)  DEFAULT NULL COMMENT '发布租约截止时间',
    `publish_fencing_token`     bigint       NOT NULL DEFAULT 0 COMMENT '发布围栏令牌',
    `publish_attempt_count`     int          NOT NULL DEFAULT 0 COMMENT '发布认领次数',
    `broker_message_id`         varchar(128) DEFAULT NULL COMMENT '订单号或终态业务标识',
    `published_at`              datetime(3)  DEFAULT NULL COMMENT '完成时间',
    `failure_category`          varchar(64)  DEFAULT NULL COMMENT '最近发布失败分类',
    `failure_message`           varchar(512) DEFAULT NULL COMMENT '最近发布失败摘要',
    `created_at`                datetime(3)  NOT NULL COMMENT '创建时间',
    `updated_at`                datetime(3)  NOT NULL COMMENT '修改时间',
    PRIMARY KEY (`namespace`, `event_id`),
    UNIQUE KEY `uk_reliable_outbox_dedupe` (`namespace`, `deduplication_key`),
    KEY `idx_reliable_outbox_publish` (`namespace`, `status`, `next_publish_at`),
    KEY `idx_reliable_outbox_lease` (`namespace`, `status`, `publish_lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可靠事务 Outbox';
