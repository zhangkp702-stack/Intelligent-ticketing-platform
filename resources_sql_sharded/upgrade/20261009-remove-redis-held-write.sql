-- 新购票链路不再将 Redis 临时预占单独写为 -1，减少一次同步 MySQL 更新。
-- -1 仅保留给滚动发布前已落库记录的恢复兼容，Redis 租约扫描负责回收新链路的临时占位。

USE `12306_ticket`;

ALTER TABLE `t_ticket_seat_reservation`
    MODIFY COLUMN `reservation_status` tinyint(1) NOT NULL DEFAULT 1
        COMMENT '生命周期：-2初始化 -1历史Redis已占位兼容态 0待绑定订单 1已绑定订单 2建单失败释放中 3建单失败已释放 4预备失败释放中 5预备失败已释放';
