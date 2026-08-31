-- 仅允许在新 Command 消费者尚未切流，且所有新增字段均未承载任务数据时回滚第二阶段结构。
-- DDL 不具备事务回滚能力。执行 DROP 前必须先核对下方查询结果，禁止对已接收任务的环境直接执行。

USE `12306_order_0`;

DROP TEMPORARY TABLE IF EXISTS `tmp_order_command_rollback_audit`;
CREATE TEMPORARY TABLE `tmp_order_command_rollback_audit`
(
    `table_schema` varchar(64) NOT NULL,
    `table_name` varchar(64) NOT NULL,
    `payload_rows` bigint NOT NULL,
    `new_status_rows` bigint NOT NULL
);

DROP PROCEDURE IF EXISTS `audit_order_command_task_model_rollback`;
DELIMITER $$
CREATE PROCEDURE `audit_order_command_task_model_rollback`()
BEGIN
    DECLARE schema_index int DEFAULT 0;
    DECLARE table_index int DEFAULT 0;
    DECLARE payload_column_exists int DEFAULT 0;

    WHILE schema_index < 2 DO
        SET table_index = 0;
        WHILE table_index < 32 DO
            SET @schema_name = CONCAT('12306_order_', schema_index);
            SET @table_name = CONCAT('t_order_command_', table_index);

            SELECT COUNT(*)
            INTO payload_column_exists
            FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = @table_name
              AND column_name = 'request_payload';

            IF payload_column_exists = 1 THEN
                SET @audit_sql = CONCAT(
                    'INSERT INTO `tmp_order_command_rollback_audit` ',
                    'SELECT ''', @schema_name, ''', ''', @table_name, ''', ',
                    'COALESCE(SUM(`request_payload` IS NOT NULL), 0), ',
                    'COALESCE(SUM(`status` IN (''RECEIVED'', ''RETRY_WAIT'', ''UNKNOWN'')), 0) ',
                    'FROM `', @schema_name, '`.`', @table_name, '`'
                );
                PREPARE audit_statement FROM @audit_sql;
                EXECUTE audit_statement;
                DEALLOCATE PREPARE audit_statement;
            END IF;

            SET table_index = table_index + 1;
        END WHILE;
        SET schema_index = schema_index + 1;
    END WHILE;
END$$
DELIMITER ;

CALL `audit_order_command_task_model_rollback`();
DROP PROCEDURE `audit_order_command_task_model_rollback`;

SELECT *
FROM `tmp_order_command_rollback_audit`
ORDER BY `table_schema`, `table_name`;

-- 回滚必须由 DBA 在确认所有 payload_rows 和 new_status_rows 都为 0 后，
-- 对实际存在的每张物理表删除以下索引和字段；这里不自动执行破坏性 DROP：
-- ALTER TABLE <physical_table>
--   DROP INDEX uk_order_command_source_message,
--   DROP INDEX idx_order_command_ready,
--   DROP INDEX idx_order_command_lease,
--   DROP INDEX idx_order_command_reservation,
--   DROP COLUMN source_message_id,
--   DROP COLUMN reservation_id,
--   DROP COLUMN message_version,
--   DROP COLUMN request_payload,
--   DROP COLUMN payload_key_version,
--   DROP COLUMN recovery_shard,
--   DROP COLUMN attempt_count,
--   DROP COLUMN max_attempts,
--   DROP COLUMN next_retry_time,
--   DROP COLUMN lease_owner,
--   DROP COLUMN lease_token,
--   DROP COLUMN lease_expire_time,
--   DROP COLUMN finish_time,
--   DROP COLUMN payload_expire_time,
--   MODIFY COLUMN status varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
--   COMMENT = '订单创建稳定命令终态表';
