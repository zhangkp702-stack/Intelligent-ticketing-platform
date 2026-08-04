-- 第六阶段 6A：为订单和退款建立可查询的稳定下游命令关联。
-- 本脚本应在 phase-8 和 phase-11 之后执行，执行前请先备份订单与支付库。

DELIMITER //

CREATE PROCEDURE upgrade_order_command_columns()
BEGIN
    DECLARE database_index INT DEFAULT 0;
    DECLARE table_index INT DEFAULT 0;
    WHILE database_index < 2 DO
        SET table_index = 0;
        WHILE table_index < 32 DO
            SET @alter_order_sql = CONCAT(
                    'ALTER TABLE `12306_order_', database_index, '`.`t_order_', table_index, '` ',
                    'ADD COLUMN `action_id` varchar(64) NULL COMMENT ''Agent action id'' AFTER `id`, ',
                    'ADD COLUMN `command_id` varchar(96) NULL COMMENT ''stable create order command'' AFTER `action_id`, ',
                    'ADD COLUMN `request_fingerprint` char(64) NULL COMMENT ''immutable request SHA-256'' AFTER `command_id`, ',
                    'ADD UNIQUE KEY `uk_order_command_id` (`command_id`) USING BTREE');
            PREPARE alter_order_statement FROM @alter_order_sql;
            EXECUTE alter_order_statement;
            DEALLOCATE PREPARE alter_order_statement;
            SET table_index = table_index + 1;
        END WHILE;
        SET database_index = database_index + 1;
    END WHILE;
END//

DELIMITER ;

CALL upgrade_order_command_columns();
DROP PROCEDURE upgrade_order_command_columns;

-- 退款命令与退款明细位于同一分库；相同请求的每条明细保存同一 action、command 和指纹。
ALTER TABLE `12306_pay_0`.`t_refund`
    ADD COLUMN `action_id` varchar(64) NULL COMMENT 'Agent action id' AFTER `id`,
    ADD COLUMN `command_id` varchar(96) NULL COMMENT 'stable refund command' AFTER `action_id`,
    ADD COLUMN `request_fingerprint` char(64) NULL COMMENT 'immutable request SHA-256' AFTER `command_id`,
    ADD KEY `idx_refund_command_id` (`command_id`) USING BTREE;

ALTER TABLE `12306_pay_1`.`t_refund`
    ADD COLUMN `action_id` varchar(64) NULL COMMENT 'Agent action id' AFTER `id`,
    ADD COLUMN `command_id` varchar(96) NULL COMMENT 'stable refund command' AFTER `action_id`,
    ADD COLUMN `request_fingerprint` char(64) NULL COMMENT 'immutable request SHA-256' AFTER `command_id`,
    ADD KEY `idx_refund_command_id` (`command_id`) USING BTREE;
