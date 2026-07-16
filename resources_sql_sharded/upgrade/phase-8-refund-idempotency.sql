-- 为已经创建的支付库补充退款请求幂等标识。
ALTER TABLE `12306_pay`.`t_refund`
    ADD COLUMN `refund_request_id` varchar(64) NULL COMMENT '退款请求幂等标识' AFTER `id`;

-- 历史数据没有请求标识，唯一索引允许多个 NULL，不影响既有退款记录。
ALTER TABLE `12306_pay`.`t_refund`
    ADD UNIQUE KEY `uk_refund_request_id_card` (`refund_request_id`, `id_card`) USING BTREE;
