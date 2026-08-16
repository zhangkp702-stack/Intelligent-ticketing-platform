-- 为关闭订单的 reservation 释放恢复扫描提供有界时间范围索引。
-- 发布顺序：先执行本脚本，再发布包含 TicketSeatReservationRecoveryService 的票务服务版本。
USE 12306_ticket;

ALTER TABLE `t_ticket_seat_reservation`
    ADD KEY `idx_reservation_release_recovery` (`update_time`);
