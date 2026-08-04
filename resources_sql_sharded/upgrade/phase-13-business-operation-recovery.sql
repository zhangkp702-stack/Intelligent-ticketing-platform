-- ticket-service 对跨服务写操作增加租约、对账和人工兜底状态。
ALTER TABLE `12306_ticket`.`t_business_operation`
    MODIFY COLUMN `status` tinyint(2) NOT NULL COMMENT '0处理中 1成功 2失败 3未知 4对账中 5人工处理',
    ADD COLUMN `lease_owner` varchar(128) DEFAULT NULL COMMENT '当前执行实例',
    ADD COLUMN `lease_until` datetime DEFAULT NULL COMMENT '执行租约截止时间',
    ADD COLUMN `execution_epoch` bigint NOT NULL DEFAULT 0 COMMENT '执行隔离版本',
    ADD COLUMN `last_heartbeat_at` datetime DEFAULT NULL COMMENT '最近心跳时间',
    ADD COLUMN `business_reference` varchar(128) DEFAULT NULL COMMENT '订单号等安全业务引用',
    ADD COLUMN `next_reconcile_at` datetime DEFAULT NULL COMMENT '下次只读对账时间',
    ADD COLUMN `reconcile_attempt_count` int NOT NULL DEFAULT 0 COMMENT '只读对账次数',
    ADD COLUMN `failure_category` varchar(64) DEFAULT NULL COMMENT '稳定失败分类',
    ADD KEY `idx_business_operation_recovery` (`status`, `lease_until`, `next_reconcile_at`);

CREATE TABLE IF NOT EXISTS `12306_ticket`.`t_business_operation_audit`
(
    `id`           bigint       NOT NULL COMMENT '审计记录标识',
    `operation_id` varchar(64)   NOT NULL COMMENT '业务操作标识',
    `operator_id`  varchar(128)  NOT NULL COMMENT '恢复器或人工操作人',
    `old_status`   tinyint(2)    NOT NULL COMMENT '原状态',
    `new_status`   tinyint(2)    NOT NULL COMMENT '新状态',
    `reason`       varchar(256)  NOT NULL COMMENT '迁移原因',
    `evidence`     varchar(500)           COMMENT '安全证据摘要',
    `create_time`  datetime      NOT NULL COMMENT '创建时间',
    `update_time`  datetime      NOT NULL COMMENT '修改时间',
    `del_flag`     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY `idx_business_operation_audit_operation` (`operation_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务操作恢复审计';
