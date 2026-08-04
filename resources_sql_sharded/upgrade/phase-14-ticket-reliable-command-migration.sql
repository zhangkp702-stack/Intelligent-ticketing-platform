-- 将 ticket-service 已有业务操作状态迁移到通用可靠命令表。
-- 部署顺序：先执行本脚本并校验回填结果，再发布只读写新表的应用版本。
CREATE TABLE IF NOT EXISTS `12306_ticket`.`t_reliable_command`
(
    `routing_key`              varchar(128) NOT NULL COMMENT '后端业务路由键',
    `namespace`                varchar(64)  NOT NULL COMMENT '命令命名空间',
    `command_id`               varchar(128) NOT NULL COMMENT '稳定命令标识',
    `command_type`             varchar(64)  NOT NULL COMMENT '稳定业务命令类型',
    `execution_mode`           varchar(32)  NOT NULL COMMENT '副作用执行模式',
    `owner_id`                 varchar(128) NOT NULL COMMENT '命令所属用户',
    `request_fingerprint`      varchar(128) NOT NULL COMMENT '规范化请求摘要',
    `fingerprint_version`      varchar(32)  NOT NULL COMMENT '摘要规则版本',
    `status`                   varchar(32)  NOT NULL COMMENT '可靠命令状态',
    `result_payload`           text                  COMMENT '成功结果序列化文本',
    `failure_category`         varchar(64)           COMMENT '失败或未知分类',
    `failure_message`          varchar(512)          COMMENT '限长故障摘要',
    `business_reference`       varchar(256)          COMMENT '订单号等安全业务引用',
    `lease_owner`              varchar(128)          COMMENT '当前租约实例',
    `lease_until`              datetime(3)           COMMENT '租约截止时间',
    `fencing_token`            bigint       NOT NULL DEFAULT 1 COMMENT '围栏令牌',
    `last_heartbeat_at`        datetime(3)           COMMENT '最近心跳时间',
    `attempt_count`            int          NOT NULL DEFAULT 1 COMMENT '执行认领次数',
    `next_reconcile_at`        datetime(3)           COMMENT '下一次对账时间',
    `reconcile_attempt_count`  int          NOT NULL DEFAULT 0 COMMENT '对账认领次数',
    `created_at`               datetime(3)  NOT NULL COMMENT '创建时间',
    `updated_at`               datetime(3)  NOT NULL COMMENT '修改时间',
    PRIMARY KEY (`routing_key`, `namespace`, `command_id`),
    KEY `idx_reliable_command_reconcile` (`namespace`, `status`, `next_reconcile_at`),
    KEY `idx_reliable_command_lease` (`namespace`, `status`, `lease_until`),
    KEY `idx_reliable_command_owner` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可靠命令执行记录';

CREATE TABLE IF NOT EXISTS `12306_ticket`.`t_reliable_command_audit`
(
    `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '审计流水主键',
    `routing_key`  varchar(128) NOT NULL COMMENT '后端业务路由键',
    `namespace`    varchar(64)  NOT NULL COMMENT '命令命名空间',
    `command_id`   varchar(128) NOT NULL COMMENT '稳定命令标识',
    `operator_id`  varchar(128) NOT NULL COMMENT '执行实例或恢复器标识',
    `old_status`   varchar(32)           COMMENT '原状态',
    `new_status`   varchar(32)  NOT NULL COMMENT '新状态',
    `reason`       varchar(128) NOT NULL COMMENT '状态迁移原因',
    `evidence`     varchar(512)          COMMENT '安全证据摘要',
    `created_at`   datetime(3)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_reliable_command_audit_key` (`routing_key`, `namespace`, `command_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可靠命令状态迁移审计';

-- 保留 ticket-v1 指纹版本以兼容历史 Fastjson + SHA-256 摘要，不在迁移时重新计算业务请求。
INSERT IGNORE INTO `12306_ticket`.`t_reliable_command`
(`routing_key`, `namespace`, `command_id`, `command_type`, `execution_mode`, `owner_id`,
 `request_fingerprint`, `fingerprint_version`, `status`, `result_payload`, `failure_category`,
 `failure_message`, `business_reference`, `lease_owner`, `lease_until`, `fencing_token`,
 `last_heartbeat_at`, `attempt_count`, `next_reconcile_at`, `reconcile_attempt_count`,
 `created_at`, `updated_at`)
SELECT `operation_id`,
       'ticket-business-operation',
       `operation_id`,
       `operation_type`,
       'REMOTE_EFFECT',
       `user_id`,
       `request_fingerprint`,
       'ticket-v1',
       CASE `status`
           WHEN 0 THEN 'PROCESSING'
           WHEN 1 THEN 'SUCCEEDED'
           WHEN 2 THEN 'FAILED'
           WHEN 3 THEN 'UNKNOWN'
           WHEN 4 THEN 'RECONCILING'
           WHEN 5 THEN 'MANUAL_REVIEW'
       END,
       `result_json`,
       `failure_category`,
       `failure_message`,
       `business_reference`,
       `lease_owner`,
       `lease_until`,
       GREATEST(COALESCE(`execution_epoch`, 1), 1),
       `last_heartbeat_at`,
       GREATEST(COALESCE(`execution_epoch`, 1), 1),
       `next_reconcile_at`,
       COALESCE(`reconcile_attempt_count`, 0),
       `create_time`,
       `update_time`
FROM `12306_ticket`.`t_business_operation`
WHERE `del_flag` = 0;

-- 历史审计只做证据迁移，不继续作为运行时状态源。
INSERT INTO `12306_ticket`.`t_reliable_command_audit`
(`routing_key`, `namespace`, `command_id`, `operator_id`, `old_status`, `new_status`,
 `reason`, `evidence`, `created_at`)
SELECT audit.`operation_id`,
       'ticket-business-operation',
       audit.`operation_id`,
       audit.`operator_id`,
       CASE audit.`old_status`
           WHEN 0 THEN 'PROCESSING'
           WHEN 1 THEN 'SUCCEEDED'
           WHEN 2 THEN 'FAILED'
           WHEN 3 THEN 'UNKNOWN'
           WHEN 4 THEN 'RECONCILING'
           WHEN 5 THEN 'MANUAL_REVIEW'
       END,
       CASE audit.`new_status`
           WHEN 0 THEN 'PROCESSING'
           WHEN 1 THEN 'SUCCEEDED'
           WHEN 2 THEN 'FAILED'
           WHEN 3 THEN 'UNKNOWN'
           WHEN 4 THEN 'RECONCILING'
           WHEN 5 THEN 'MANUAL_REVIEW'
       END,
       audit.`reason`,
       audit.`evidence`,
       audit.`create_time`
FROM `12306_ticket`.`t_business_operation_audit` audit
WHERE audit.`del_flag` = 0
  AND NOT EXISTS (
      SELECT 1
      FROM `12306_ticket`.`t_reliable_command_audit` migrated
      WHERE migrated.`routing_key` = audit.`operation_id`
        AND migrated.`namespace` = 'ticket-business-operation'
        AND migrated.`command_id` = audit.`operation_id`
        AND migrated.`operator_id` = audit.`operator_id`
        AND migrated.`reason` = audit.`reason`
        AND migrated.`created_at` = audit.`create_time`
  );

-- 发布前校验：两边有效命令数量以及各状态数量必须一致。
SELECT COUNT(*) AS `legacy_operation_count`
FROM `12306_ticket`.`t_business_operation`
WHERE `del_flag` = 0;

SELECT COUNT(*) AS `reliable_command_count`
FROM `12306_ticket`.`t_reliable_command`
WHERE `namespace` = 'ticket-business-operation';
