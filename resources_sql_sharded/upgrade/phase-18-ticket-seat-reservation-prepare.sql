-- 将座位占用记录拆分为远程调用前 PREPARED 和订单返回后 BOUND 两个阶段。
-- 发布顺序：先执行本脚本，再发布包含普通购票稳定命令和三段式事务的票务服务版本。
USE 12306_ticket;

ALTER TABLE `t_ticket_seat_reservation`
    MODIFY COLUMN `order_sn` varchar(128) DEFAULT NULL COMMENT '订单号，待绑定阶段为空',
    ADD COLUMN `action_id` varchar(128) DEFAULT NULL COMMENT '订单创建动作标识' AFTER `reservation_id`,
    ADD COLUMN `command_id` varchar(160) DEFAULT NULL COMMENT '订单创建稳定命令标识' AFTER `action_id`,
    ADD COLUMN `user_id` varchar(64) DEFAULT NULL COMMENT '发起购票的用户标识' AFTER `command_id`,
    ADD COLUMN `username` varchar(256) DEFAULT NULL COMMENT '发起购票的用户名' AFTER `user_id`,
    ADD COLUMN `reservation_status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '生命周期：0待绑定订单 1已绑定订单' AFTER `order_sn`,
    ADD UNIQUE KEY `uk_command_id` (`command_id`),
    DROP KEY `idx_reservation_release_recovery`,
    ADD KEY `idx_reservation_release_recovery` (`reservation_status`, `update_time`);
