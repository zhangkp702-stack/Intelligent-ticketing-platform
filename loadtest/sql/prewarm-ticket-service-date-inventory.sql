-- 为 JMeter 随机车次、随机日期场景预生成运行库存。
-- 依赖 resources_sql_sharded/upgrade/phase-16-ticket-service-date-inventory.sql 已执行。
-- 默认覆盖车次 1001-1019，以及今天至未来 14 天；脚本可重复执行且不会覆盖已有占用位图。

USE 12306_ticket;

SET @prewarm_start_date = CURDATE();
SET @prewarm_end_date = DATE_ADD(CURDATE(), INTERVAL 14 DAY);
SET @prewarm_train_id_min = 1001;
SET @prewarm_train_id_max = 1019;

DROP PROCEDURE IF EXISTS prewarm_ticket_service_date_inventory;

DELIMITER //

CREATE PROCEDURE prewarm_ticket_service_date_inventory()
BEGIN
    DECLARE current_service_date DATE;

    SET current_service_date = @prewarm_start_date;
    WHILE current_service_date <= @prewarm_end_date DO
        -- 运行库存只从静态座位布局复制一次，已有行通过唯一键保持原占用状态和版本号。
        INSERT IGNORE INTO t_train_seat_occupancy
            (train_id, service_date, seat_id, occupy_bitmap, version, create_time, update_time, del_flag)
        SELECT seat.train_id,
               current_service_date,
               seat.id,
               0,
               0,
               NOW(),
               NOW(),
               0
        FROM t_seat seat
        WHERE seat.train_id BETWEEN @prewarm_train_id_min AND @prewarm_train_id_max
          AND (seat.del_flag = 0 OR seat.del_flag IS NULL);

        SET current_service_date = DATE_ADD(current_service_date, INTERVAL 1 DAY);
    END WHILE;
END//

DELIMITER ;

CALL prewarm_ticket_service_date_inventory();
DROP PROCEDURE prewarm_ticket_service_date_inventory;

-- 每个日期的 actual_inventory_rows 应等于所选车次静态座位总数，否则购票接口会按库存未就绪快速失败。
SELECT service_date,
       COUNT(DISTINCT train_id) AS ready_train_count,
       COUNT(*) AS actual_inventory_rows,
       (SELECT COUNT(*)
        FROM t_seat
        WHERE train_id BETWEEN @prewarm_train_id_min AND @prewarm_train_id_max
          AND (del_flag = 0 OR del_flag IS NULL)) AS expected_inventory_rows
FROM t_train_seat_occupancy
WHERE train_id BETWEEN @prewarm_train_id_min AND @prewarm_train_id_max
  AND service_date BETWEEN @prewarm_start_date AND @prewarm_end_date
  AND del_flag = 0
GROUP BY service_date
ORDER BY service_date;



SELECT
    MIN(service_date) AS min_date,
    MAX(service_date) AS max_date,
    COUNT(DISTINCT service_date) AS date_count,
    COUNT(DISTINCT train_id) AS train_count,
    COUNT(*) AS inventory_rows
FROM 12306_ticket.t_train_seat_occupancy
WHERE train_id BETWEEN 1001 AND 1019
  AND service_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 14 DAY)
  AND del_flag = 0;