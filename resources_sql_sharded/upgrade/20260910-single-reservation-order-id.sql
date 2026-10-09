-- 将新购票链路收敛为一个雪花业务 ID：reservation_id = order_sn。
--
-- 执行窗口：先停止 V1 写流量并确认 Canal 已消费旧 binlog，再执行本脚本，随后一次性部署
-- ticket-order-contract、ticket-service、order-service 和 canal-relay-service 的 V2 代码并恢复流量。
-- 本脚本只删除已由 reservation_id 可推导的 V1 字段；t_ticket_seat_reservation.order_sn 暂时保留，
-- 供历史延迟关单回退查询。待历史订单完全过期且下一版代码移除回退逻辑后，再单独删除该列。
--
-- 执行前请备份，并在生产大表使用在线 DDL 工具执行等价变更。本脚本要求 MySQL 8.0。

USE `12306_ticket`;

-- 第一阶段：锁座表仅保留 reservation_id 作为新链路主键。
DROP PROCEDURE IF EXISTS `upgrade_ticket_single_reservation_id`;
DELIMITER $$
CREATE PROCEDURE `upgrade_ticket_single_reservation_id`()
BEGIN
    DECLARE legacy_column_count int DEFAULT 0;
    DECLARE pending_reservation_count bigint DEFAULT 0;

    SELECT COUNT(*)
    INTO legacy_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_ticket_seat_reservation'
      AND column_name IN ('action_id', 'command_id');

    IF legacy_column_count = 2 THEN
        -- 旧 PREPARED 记录依赖 command_id 对账；删除前必须由 V1 消费器处理完毕或人工终结。
        SELECT COUNT(*)
        INTO pending_reservation_count
        FROM t_ticket_seat_reservation
        WHERE del_flag = 0
          AND reservation_status = 0
          AND command_id IS NOT NULL
          AND command_id <> '';

        IF pending_reservation_count <> 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = '存在未终态的 V1 座位预留，请先完成建单对账或释放后再迁移';
        END IF;

        ALTER TABLE t_ticket_seat_reservation
            DROP INDEX uk_command_id,
            DROP COLUMN action_id,
            DROP COLUMN command_id,
            MODIFY COLUMN reservation_id varchar(64) NOT NULL COMMENT '雪花算法生成的购票受理标识，新协议同时作为订单号',
            MODIFY COLUMN order_sn varchar(128) DEFAULT NULL COMMENT '历史订单号兼容字段；新协议不再写入';
    ELSEIF legacy_column_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 't_ticket_seat_reservation 处于部分迁移状态，请先人工核对';
    END IF;
END$$
DELIMITER ;

CALL `upgrade_ticket_single_reservation_id`();
DROP PROCEDURE `upgrade_ticket_single_reservation_id`;

-- 第二阶段：按真实路由升级 32 个订单逻辑分片。
-- t_order / t_order_item 等表的真实布局是 order_0: 0~15、order_1: 16~31。
-- 因此不能按同一个库、同一个表号强行将 t_order 与 t_order_command 配对。
USE `12306_order_0`;

DROP PROCEDURE IF EXISTS `upgrade_order_single_reservation_id`;
DELIMITER $$
CREATE PROCEDURE `upgrade_order_single_reservation_id`()
BEGIN
    DECLARE table_index int DEFAULT 0;
    DECLARE order_legacy_column_count int DEFAULT 0;
    DECLARE command_legacy_column_count int DEFAULT 0;
    DECLARE duplicate_order_sn_count bigint DEFAULT 0;
    DECLARE duplicate_reservation_count bigint DEFAULT 0;
    DECLARE active_command_count bigint DEFAULT 0;

    WHILE table_index < 32 DO
        SET @schema_name = CONCAT('12306_order_', FLOOR(table_index / 16));
        SET @order_table_name = CONCAT('t_order_', table_index);
        SET @command_table_name = CONCAT('t_order_command_', table_index);

        SELECT COUNT(*) INTO order_legacy_column_count
        FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = @order_table_name
          AND column_name IN ('action_id', 'command_id');

        IF order_legacy_column_count = 2 THEN
            SET duplicate_order_sn_count = 0;
            SET @duplicate_order_sql = CONCAT(
                'SELECT COUNT(*) INTO @duplicate_order_sn_count FROM (',
                'SELECT order_sn FROM `', @schema_name, '`.`', @order_table_name, '` ',
                'WHERE order_sn IS NOT NULL AND order_sn <> '''' ',
                'GROUP BY order_sn HAVING COUNT(*) > 1) duplicate_order_sn'
            );
            PREPARE duplicate_order_statement FROM @duplicate_order_sql;
            EXECUTE duplicate_order_statement;
            DEALLOCATE PREPARE duplicate_order_statement;
            SET duplicate_order_sn_count = @duplicate_order_sn_count;

            IF duplicate_order_sn_count <> 0 THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = '存在重复 order_sn，不能创建订单号唯一索引';
            END IF;

            SET @upgrade_order_sql = CONCAT(
                'ALTER TABLE `', @schema_name, '`.`', @order_table_name, '` ',
                'DROP INDEX uk_order_command_id, ',
                'DROP INDEX idx_order_sn, ',
                'DROP COLUMN action_id, ',
                'DROP COLUMN command_id, ',
                'MODIFY COLUMN order_sn varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''雪花订单号，同时等于 reservation_id'', ',
                'ADD UNIQUE KEY uk_order_sn (order_sn) USING BTREE'
            );
            PREPARE upgrade_order_statement FROM @upgrade_order_sql;
            EXECUTE upgrade_order_statement;
            DEALLOCATE PREPARE upgrade_order_statement;
        ELSEIF order_legacy_column_count <> 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 't_order 处于部分迁移状态，请先人工核对';
        END IF;

        SELECT COUNT(*) INTO command_legacy_column_count
        FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = @command_table_name
          AND column_name IN ('command_id', 'action_id', 'source_message_id', 'order_sn');

        IF command_legacy_column_count = 4 THEN
                SET @active_command_sql = CONCAT(
                    'SELECT COUNT(*) INTO @active_command_count FROM `', @schema_name, '`.`', @command_table_name, '` ',
                    'WHERE del_flag = 0 AND status IN (''RECEIVED'', ''PROCESSING'', ''RETRY_WAIT'', ''UNKNOWN'')'
                );
                PREPARE active_command_statement FROM @active_command_sql;
                EXECUTE active_command_statement;
                DEALLOCATE PREPARE active_command_statement;
                SET active_command_count = @active_command_count;

                IF active_command_count <> 0 THEN
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = '存在未终态的 V1 订单创建任务，请先完成消费或对账后再迁移';
                END IF;

                SET @duplicate_reservation_sql = CONCAT(
                    'SELECT COUNT(*) INTO @duplicate_reservation_count FROM (',
                    'SELECT reservation_id FROM `', @schema_name, '`.`', @command_table_name, '` ',
                    'WHERE reservation_id IS NOT NULL AND reservation_id <> '''' ',
                    'GROUP BY reservation_id HAVING COUNT(*) > 1) duplicate_reservation'
                );
                PREPARE duplicate_reservation_statement FROM @duplicate_reservation_sql;
                EXECUTE duplicate_reservation_statement;
                DEALLOCATE PREPARE duplicate_reservation_statement;
                SET duplicate_reservation_count = @duplicate_reservation_count;

                IF duplicate_reservation_count <> 0 THEN
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = '存在重复 reservation_id，不能创建建单任务唯一索引';
                END IF;

                SET @upgrade_command_sql = CONCAT(
                    'ALTER TABLE `', @schema_name, '`.`', @command_table_name, '` ',
                    'DROP INDEX uk_order_command_id, ',
                    'DROP INDEX uk_order_command_source_message, ',
                    'DROP INDEX idx_order_command_reservation, ',
                    'DROP COLUMN command_id, ',
                    'DROP COLUMN action_id, ',
                    'DROP COLUMN source_message_id, ',
                    'DROP COLUMN order_sn, ',
                    'MODIFY COLUMN reservation_id varchar(64) DEFAULT NULL COMMENT ''雪花购票受理标识，同时作为建单任务幂等键和订单号'', ',
                    'ADD UNIQUE KEY uk_order_command_reservation (reservation_id)'
                );
                PREPARE upgrade_command_statement FROM @upgrade_command_sql;
                EXECUTE upgrade_command_statement;
                DEALLOCATE PREPARE upgrade_command_statement;
        ELSEIF command_legacy_column_count <> 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 't_order_command 处于部分迁移状态，请先人工核对';
        END IF;

        SET table_index = table_index + 1;
    END WHILE;
END$$
DELIMITER ;

CALL `upgrade_order_single_reservation_id`();
DROP PROCEDURE `upgrade_order_single_reservation_id`;

-- Outbox 的 command_id 是通用路由列，不再代表第二个业务 ID；V2 代码统一写 reservation_id。
-- 只修改字段注释，不改变既有 CDC 表结构，避免影响 Canal 的通用解析和历史事件。
DROP PROCEDURE IF EXISTS `annotate_outbox_reservation_routing_id`;
DELIMITER $$
CREATE PROCEDURE `annotate_outbox_reservation_routing_id`()
BEGIN
    DECLARE table_index int DEFAULT 0;

    WHILE table_index < 32 DO
        SET @schema_name = CONCAT('12306_order_', FLOOR(table_index / 16));
        SET @outbox_table_name = CONCAT('t_cdc_outbox_event_', table_index);
        SET @annotate_outbox_sql = CONCAT(
            'ALTER TABLE `', @schema_name, '`.`', @outbox_table_name, '` ',
            'MODIFY COLUMN command_id varchar(160) NOT NULL COMMENT ''订单创建路由标识；V2 统一填写 reservation_id'''
        );
        PREPARE annotate_outbox_statement FROM @annotate_outbox_sql;
        EXECUTE annotate_outbox_statement;
        DEALLOCATE PREPARE annotate_outbox_statement;
        SET table_index = table_index + 1;
    END WHILE;
END$$
DELIMITER ;

CALL `annotate_outbox_reservation_routing_id`();
DROP PROCEDURE `annotate_outbox_reservation_routing_id`;

-- 第三阶段：清理错误创建且从未被当前分片算法路由的空表。
-- 已核验当前库中以下 64 张表均为 0 行；若其他环境存在数据，脚本会拒绝删除。
DROP PROCEDURE IF EXISTS `drop_unused_order_side_tables`;
DELIMITER $$
CREATE PROCEDURE `drop_unused_order_side_tables`()
BEGIN
    DECLARE schema_index int DEFAULT 0;
    DECLARE table_index int DEFAULT 0;
    DECLARE row_count bigint DEFAULT 0;
    DECLARE table_exists int DEFAULT 0;

    WHILE schema_index < 2 DO
        SET table_index = 0;
        WHILE table_index < 32 DO
            IF (schema_index = 0 AND table_index >= 16) OR (schema_index = 1 AND table_index < 16) THEN
                SET @schema_name = CONCAT('12306_order_', schema_index);
                SET @command_table_name = CONCAT('t_order_command_', table_index);
                SET @outbox_table_name = CONCAT('t_cdc_outbox_event_', table_index);

                SELECT COUNT(*) INTO table_exists
                FROM information_schema.tables
                WHERE table_schema = @schema_name AND table_name = @command_table_name;
                IF table_exists = 1 THEN
                    SET @count_command_sql = CONCAT('SELECT COUNT(*) INTO @unused_table_row_count FROM `', @schema_name, '`.`', @command_table_name, '`');
                    PREPARE count_command_statement FROM @count_command_sql;
                    EXECUTE count_command_statement;
                    DEALLOCATE PREPARE count_command_statement;
                    SET row_count = @unused_table_row_count;
                    IF row_count <> 0 THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = '未路由 t_order_command 分表存在数据，拒绝删除';
                    END IF;
                    SET @drop_command_sql = CONCAT('DROP TABLE `', @schema_name, '`.`', @command_table_name, '`');
                    PREPARE drop_command_statement FROM @drop_command_sql;
                    EXECUTE drop_command_statement;
                    DEALLOCATE PREPARE drop_command_statement;
                END IF;

                SELECT COUNT(*) INTO table_exists
                FROM information_schema.tables
                WHERE table_schema = @schema_name AND table_name = @outbox_table_name;
                IF table_exists = 1 THEN
                    SET @count_outbox_sql = CONCAT('SELECT COUNT(*) INTO @unused_table_row_count FROM `', @schema_name, '`.`', @outbox_table_name, '`');
                    PREPARE count_outbox_statement FROM @count_outbox_sql;
                    EXECUTE count_outbox_statement;
                    DEALLOCATE PREPARE count_outbox_statement;
                    SET row_count = @unused_table_row_count;
                    IF row_count <> 0 THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = '未路由 t_cdc_outbox_event 分表存在数据，拒绝删除';
                    END IF;
                    SET @drop_outbox_sql = CONCAT('DROP TABLE `', @schema_name, '`.`', @outbox_table_name, '`');
                    PREPARE drop_outbox_statement FROM @drop_outbox_sql;
                    EXECUTE drop_outbox_statement;
                    DEALLOCATE PREPARE drop_outbox_statement;
                END IF;
            END IF;
            SET table_index = table_index + 1;
        END WHILE;
        SET schema_index = schema_index + 1;
    END WHILE;
END$$
DELIMITER ;

CALL `drop_unused_order_side_tables`();
DROP PROCEDURE `drop_unused_order_side_tables`;

-- 验收：每张订单表都应只有 uk_order_sn；每张建单任务表都应只有 uk_order_command_reservation。
SELECT table_schema, table_name, index_name
FROM information_schema.statistics
WHERE table_schema IN ('12306_order_0', '12306_order_1')
  AND table_name REGEXP '^t_order(_command)?_[0-9]+$'
  AND index_name IN ('uk_order_sn', 'uk_order_command_reservation')
ORDER BY table_schema, table_name, index_name;

-- 灰度观察期验收：新协议写入的订单号、锁座 ID 和 Outbox 路由 ID 必须相同。
-- SELECT reservation_id, order_sn FROM t_ticket_seat_reservation
-- WHERE create_time >= '<V2 上线时间>' AND reservation_status = 1 AND reservation_id <> order_sn;
-- 上述查询应返回 0 行；历史行 order_sn 与 reservation_id 不等是预期兼容数据。
