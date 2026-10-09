-- 第一阶段先引入 Redis 占位之前可独立提交的 INIT/REDIS_HELD 状态。
-- 本次迁移不改变列类型和索引，只补充状态含义；tinyint 已能保存 -2 到 5。

USE `12306_ticket`;

ALTER TABLE `t_ticket_seat_reservation`
    MODIFY COLUMN `reservation_status` tinyint(1) NOT NULL DEFAULT 1
        COMMENT '生命周期：-2初始化 -1 Redis已占位 0待绑定订单 1已绑定订单 2建单失败释放中 3建单失败已释放 4预备失败释放中 5预备失败已释放';
