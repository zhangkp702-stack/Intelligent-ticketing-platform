-- 阶段一：运行库存按“车次 + 始发日期”隔离。
-- 先执行本脚本，再部署包含 service_date 写入逻辑的 ticket-service。

USE 12306_ticket;

ALTER TABLE `t_ticket_seat_reservation`
    ADD COLUMN `service_date` date DEFAULT NULL COMMENT '列车始发日期' AFTER `riding_date`,
    ADD KEY `idx_train_service_date` (`train_id`, `service_date`);

-- t_seat 保留为静态座位布局；该表将在下一阶段按首次访问的始发日期初始化运行占用位图。
CREATE TABLE IF NOT EXISTS `t_train_seat_occupancy`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `train_id`       bigint(20) unsigned NOT NULL COMMENT '列车ID',
    `service_date`   date                 NOT NULL COMMENT '列车始发日期',
    `seat_id`        bigint(20) unsigned NOT NULL COMMENT '静态座位ID',
    `occupy_bitmap`  bigint(20)          NOT NULL DEFAULT 0 COMMENT '区间占用位图',
    `version`        bigint(20)          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `create_time`    datetime                     DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                     DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1)                   DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_train_service_seat` (`train_id`, `service_date`, `seat_id`),
    KEY `idx_train_service_date` (`train_id`, `service_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='列车指定始发日期的座位运行库存表';
