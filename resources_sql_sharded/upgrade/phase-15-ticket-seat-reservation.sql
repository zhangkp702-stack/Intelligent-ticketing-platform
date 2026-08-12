-- 为订单关闭的异步座位释放建立 reservation 持久化状态机。
-- 发布顺序：先执行本脚本，再发布依赖 reservationId 的票务服务版本。
USE 12306_ticket;

CREATE TABLE IF NOT EXISTS `t_ticket_seat_reservation`
(
    `id`                          bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `reservation_id`              varchar(64)  NOT NULL COMMENT '不可复用的座位占用标识',
    `order_sn`                    varchar(128) NOT NULL COMMENT '订单号',
    `train_id`                    bigint(20)   NOT NULL COMMENT '列车ID',
    `riding_date`                 date         DEFAULT NULL COMMENT '乘车日期',
    `departure`                   varchar(64)  NOT NULL COMMENT '出发站',
    `arrival`                     varchar(64)  NOT NULL COMMENT '到达站',
    `seat_payload`                mediumtext   NOT NULL COMMENT '座位明细JSON快照',
    `db_seat_release_status`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '数据库座位释放状态：0待处理 1完成',
    `redis_bitmap_release_status` tinyint(1)   NOT NULL DEFAULT 0 COMMENT 'Redis位图释放状态：0待处理 1完成 2owner已变化',
    `token_rollback_status`       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '令牌桶回滚状态：0待处理 1完成',
    `create_time`                 datetime     DEFAULT NULL COMMENT '创建时间',
    `update_time`                 datetime     DEFAULT NULL COMMENT '修改时间',
    `del_flag`                    tinyint(1)   DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_reservation_id` (`reservation_id`),
    KEY `idx_order_sn` (`order_sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单座位占用及释放状态表';

-- 历史订单在旧版本中没有 reservation 归属信息，不能通过座位号反推后无条件释放。
-- 请在发布前等待历史待支付订单自然关闭，或由运维按订单明细人工创建核对后的 reservation 记录。
