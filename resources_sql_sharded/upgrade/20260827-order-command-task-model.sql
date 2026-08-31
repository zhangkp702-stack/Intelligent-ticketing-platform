-- 将 t_order_command 从命令终态记录扩展为可独立恢复的订单创建任务。
-- 执行前提：MySQL 8.0；先完成第一阶段只读审计，并确认全部 Command 物理表为空。
-- 生产大表应由 DBA 使用在线 DDL 工具执行等价变更，本脚本用于新环境和受控维护窗口。

USE `12306_order_0`;

DROP PROCEDURE IF EXISTS `upgrade_order_command_task_model`;
DELIMITER $$
CREATE PROCEDURE `upgrade_order_command_task_model`()
BEGIN
    DECLARE schema_index int DEFAULT 0;
    DECLARE table_index int DEFAULT 0;
    DECLARE table_exists int DEFAULT 0;
    DECLARE model_column_count int DEFAULT 0;

    WHILE schema_index < 2 DO
        SET table_index = 0;
        WHILE table_index < 32 DO
            SET @schema_name = CONCAT('12306_order_', schema_index);
            SET @table_name = CONCAT('t_order_command_', table_index);

            SELECT COUNT(*)
            INTO table_exists
            FROM information_schema.tables
            WHERE table_schema = @schema_name
              AND table_name = @table_name;

            IF table_exists = 1 THEN
                SELECT COUNT(*)
                INTO model_column_count
                FROM information_schema.columns
                WHERE table_schema = @schema_name
                  AND table_name = @table_name
                  AND column_name IN (
                      'source_message_id', 'reservation_id', 'message_version', 'request_payload',
                      'payload_key_version', 'recovery_shard', 'attempt_count', 'max_attempts', 'next_retry_time',
                      'lease_owner', 'lease_token', 'lease_expire_time', 'finish_time', 'payload_expire_time'
                  );

                IF model_column_count = 0 THEN
                    SET @command_row_count = 0;
                    SET @count_sql = CONCAT(
                        'SELECT COUNT(*) INTO @command_row_count FROM `', @schema_name, '`.`', @table_name, '`'
                    );
                    PREPARE count_statement FROM @count_sql;
                    EXECUTE count_statement;
                    DEALLOCATE PREPARE count_statement;

                    IF @command_row_count <> 0 THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = '检测到历史 t_order_command 记录，无法安全计算 recovery_shard，请先迁移存量数据';
                    END IF;

                    SET @ddl = CONCAT(
                        'ALTER TABLE `', @schema_name, '`.`', @table_name, '` ',
                        'ADD COLUMN `source_message_id` varchar(128) DEFAULT NULL COMMENT ''票务服务源消息标识'' AFTER `request_fingerprint`, ',
                        'ADD COLUMN `reservation_id` varchar(64) DEFAULT NULL COMMENT ''票务座位预留标识'' AFTER `source_message_id`, ',
                        'ADD COLUMN `message_version` bigint(20) DEFAULT NULL COMMENT ''订单创建消息协议版本'' AFTER `reservation_id`, ',
                        'ADD COLUMN `request_payload` mediumtext COMMENT ''经整列加密的可恢复订单创建请求'' AFTER `message_version`, ',
                        'ADD COLUMN `payload_key_version` smallint unsigned DEFAULT NULL COMMENT ''请求载荷加密密钥版本'' AFTER `request_payload`, ',
                        'ADD COLUMN `recovery_shard` tinyint unsigned NOT NULL COMMENT ''物理恢复分片编号'' AFTER `user_id`, ',
                        'MODIFY COLUMN `status` varchar(16) NOT NULL COMMENT ''RECEIVED、PROCESSING、RETRY_WAIT、UNKNOWN、SUCCEEDED 或 FAILED'', ',
                        'ADD COLUMN `attempt_count` int unsigned NOT NULL DEFAULT 0 COMMENT ''已执行次数'' AFTER `status`, ',
                        'ADD COLUMN `max_attempts` int unsigned NOT NULL DEFAULT 16 COMMENT ''最大执行次数'' AFTER `attempt_count`, ',
                        'ADD COLUMN `next_retry_time` datetime DEFAULT NULL COMMENT ''下一次允许认领时间'' AFTER `max_attempts`, ',
                        'ADD COLUMN `lease_owner` varchar(128) DEFAULT NULL COMMENT ''当前执行租约所有者'' AFTER `next_retry_time`, ',
                        'ADD COLUMN `lease_token` bigint unsigned NOT NULL DEFAULT 0 COMMENT ''单调递增租约围栏'' AFTER `lease_owner`, ',
                        'ADD COLUMN `lease_expire_time` datetime DEFAULT NULL COMMENT ''当前执行租约到期时间'' AFTER `lease_token`, ',
                        'ADD COLUMN `finish_time` datetime DEFAULT NULL COMMENT ''命令终态完成时间'' AFTER `failure_reason`, ',
                        'ADD COLUMN `payload_expire_time` datetime DEFAULT NULL COMMENT ''终态请求载荷允许清理时间'' AFTER `finish_time`, ',
                        'ADD UNIQUE KEY `uk_order_command_source_message` (`source_message_id`), ',
                        'ADD KEY `idx_order_command_ready` (`recovery_shard`, `status`, `next_retry_time`, `id`), ',
                        'ADD KEY `idx_order_command_lease` (`recovery_shard`, `status`, `lease_expire_time`, `id`), ',
                        'ADD KEY `idx_order_command_reservation` (`reservation_id`), ',
                        'COMMENT = ''订单创建可靠任务表'''
                    );
                    PREPARE upgrade_statement FROM @ddl;
                    EXECUTE upgrade_statement;
                    DEALLOCATE PREPARE upgrade_statement;
                ELSEIF model_column_count <> 14 THEN
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = '检测到部分完成的 t_order_command 任务模型迁移，请先人工核对';
                END IF;
            END IF;

            SET table_index = table_index + 1;
        END WHILE;
        SET schema_index = schema_index + 1;
    END WHILE;
END$$
DELIMITER ;

CALL `upgrade_order_command_task_model`();
DROP PROCEDURE `upgrade_order_command_task_model`;

-- 结果包含全部实际物理表；model_column_count 必须为 14，model_index_count 必须为 4。
SELECT command_table.table_schema,
       command_table.table_name,
       COUNT(DISTINCT command_column.column_name) AS model_column_count,
       COUNT(DISTINCT command_index.index_name) AS model_index_count
FROM information_schema.tables command_table
         LEFT JOIN information_schema.columns command_column
                   ON command_column.table_schema = command_table.table_schema
                       AND command_column.table_name = command_table.table_name
                       AND command_column.column_name IN (
      'source_message_id', 'reservation_id', 'message_version', 'request_payload',
      'payload_key_version', 'recovery_shard', 'attempt_count', 'max_attempts', 'next_retry_time',
      'lease_owner', 'lease_token', 'lease_expire_time', 'finish_time', 'payload_expire_time'
                           )
         LEFT JOIN information_schema.statistics command_index
                   ON command_index.table_schema = command_table.table_schema
                       AND command_index.table_name = command_table.table_name
                       AND command_index.index_name IN (
                           'uk_order_command_source_message', 'idx_order_command_ready',
                           'idx_order_command_lease', 'idx_order_command_reservation'
                           )
WHERE command_table.table_schema IN ('12306_order_0', '12306_order_1')
  AND command_table.table_name LIKE 't_order_command\_%'
GROUP BY command_table.table_schema, command_table.table_name
ORDER BY command_table.table_schema, command_table.table_name;

-- 上线新消费者前必须确认没有旧状态数据；存在记录时应先执行兼容回填或人工修复。
-- 请在每个实际物理表执行：
-- SELECT status, COUNT(*) FROM <physical_table> GROUP BY status;
