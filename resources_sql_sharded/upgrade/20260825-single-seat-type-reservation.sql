-- 为座位预留记录补充订单级席别和座位数量。
-- 本迁移要求 MySQL 8.0，以便使用 JSON_TABLE 审计既有座位快照。
ALTER TABLE `t_ticket_seat_reservation`
    ADD COLUMN `seat_type` int DEFAULT NULL COMMENT '本次购票统一使用的座位类型' AFTER `arrival`,
    ADD COLUMN `seat_count` int DEFAULT NULL COMMENT '本次占用的座位数量' AFTER `seat_type`;

-- 只回填非空且全部座位属于同一席别的历史记录；混席或损坏数据保持 NULL，阻止后续约束收口。
UPDATE `t_ticket_seat_reservation` reservation
    INNER JOIN (
        SELECT source.id,
               MIN(ticket.seat_type) AS seat_type,
               COUNT(*) AS seat_count
        FROM `t_ticket_seat_reservation` source
                 INNER JOIN JSON_TABLE(
                    IF(JSON_VALID(source.seat_payload), source.seat_payload, JSON_ARRAY()),
                    '$[*]' COLUMNS (
                        seat_type int PATH '$.seatType'
                    )
                 ) ticket
        GROUP BY source.id
        HAVING COUNT(*) > 0
           AND COUNT(DISTINCT ticket.seat_type) = 1
    ) valid_snapshot ON valid_snapshot.id = reservation.id
SET reservation.seat_type = valid_snapshot.seat_type,
    reservation.seat_count = valid_snapshot.seat_count;

-- 此查询必须返回零行；否则应先人工处理历史混席或损坏快照，再继续执行约束收口。
SELECT `id`, `reservation_id`, `seat_payload`
FROM `t_ticket_seat_reservation`
WHERE `seat_type` IS NULL
   OR `seat_count` IS NULL
   OR `seat_count` <= 0;

-- 未通过上方审计的数据会在这里导致迁移失败，避免运行时按第一张票静默推导订单席别。
ALTER TABLE `t_ticket_seat_reservation`
    MODIFY COLUMN `seat_type` int NOT NULL COMMENT '本次购票统一使用的座位类型',
    MODIFY COLUMN `seat_count` int NOT NULL COMMENT '本次占用的座位数量';
