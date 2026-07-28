-- 先升级现有的 pay_0 退款表，再复制同结构表到 pay_1。
ALTER TABLE `12306_pay_0`.`t_refund`
    ADD COLUMN `refund_request_id` varchar(64) NULL COMMENT '退款请求幂等标识' AFTER `id`;

-- 历史数据没有请求标识，唯一索引允许多个 NULL，不影响既有退款记录。
ALTER TABLE `12306_pay_0`.`t_refund`
    ADD UNIQUE KEY `uk_refund_request_id_card` (`refund_request_id`, `id_card`) USING BTREE;

-- 退款记录按订单号与支付单使用相同分库算法，但每个库只保留一张退款表。
CREATE TABLE `12306_pay_1`.`t_refund` LIKE `12306_pay_0`.`t_refund`;
