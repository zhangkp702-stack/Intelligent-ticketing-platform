-- 将订单命令表扩展为延迟关单 Outbox；订单成功状态和待发送状态在同一事务提交。
-- 先执行本脚本，再发布包含异步投递器的 order-service。

DELIMITER //

CREATE PROCEDURE `12306_order_0`.upgrade_order_delay_close_outbox()
BEGIN
    DECLARE database_index INT DEFAULT 0;
    DECLARE table_index INT DEFAULT 0;
    DECLARE current_schema VARCHAR(64);
    DECLARE current_table VARCHAR(64);

    WHILE database_index < 2 DO
        SET table_index = 0;
        WHILE table_index < 32 DO
            SET current_schema = CONCAT('12306_order_', database_index);
            SET current_table = CONCAT('t_order_command_', table_index);

            SELECT COUNT(*) INTO @column_exists
            FROM information_schema.columns
            WHERE table_schema = current_schema
              AND table_name = current_table
              AND column_name = 'delay_close_status';

            IF @column_exists = 0 THEN
                SET @upgrade_outbox_sql = CONCAT(
                        'ALTER TABLE `', current_schema, '`.`', current_table, '` ',
                        'ADD COLUMN `delay_close_status` tinyint(1) DEFAULT NULL COMMENT ''延迟关单消息：0待发送 1发送中 2已发送'' AFTER `failure_reason`, ',
                        'ADD COLUMN `delay_close_retry_count` int NOT NULL DEFAULT 0 COMMENT ''延迟关单消息失败次数'' AFTER `delay_close_status`, ',
                        'ADD COLUMN `delay_close_next_retry_time` datetime DEFAULT NULL COMMENT ''下次重试或发送租约到期时间'' AFTER `delay_close_retry_count`, ',
                        'ADD COLUMN `delay_close_failure_reason` varchar(128) DEFAULT NULL COMMENT ''最近一次发送失败摘要'' AFTER `delay_close_next_retry_time`, ',
                        'ADD KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)');
                PREPARE upgrade_outbox_statement FROM @upgrade_outbox_sql;
                EXECUTE upgrade_outbox_statement;
                DEALLOCATE PREPARE upgrade_outbox_statement;
            END IF;

            SET table_index = table_index + 1;
        END WHILE;
        SET database_index = database_index + 1;
    END WHILE;
END//

DELIMITER ;

CALL `12306_order_0`.upgrade_order_delay_close_outbox();
DROP PROCEDURE `12306_order_0`.upgrade_order_delay_close_outbox;
