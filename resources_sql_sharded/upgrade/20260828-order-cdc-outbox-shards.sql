-- 第二阶段：在一个订单分库中创建 32 张与 t_order_command 同路由的 CDC Outbox 分表。
-- 使用方式：
--   1. 分别连接并执行：USE `12306_order_0`; 再执行本脚本。
--   2. 分别连接并执行：USE `12306_order_1`; 再执行本脚本。
-- 本脚本不迁移、不删除旧 Outbox 数据，可重复执行。

DROP PROCEDURE IF EXISTS `upgrade_order_cdc_outbox_shards`;
DELIMITER $$
CREATE PROCEDURE `upgrade_order_cdc_outbox_shards`()
BEGIN
    DECLARE table_index int DEFAULT 0;
    DECLARE current_schema varchar(64);
    DECLARE command_table_count int DEFAULT 0;

    SET current_schema = DATABASE();
    IF current_schema NOT IN ('12306_order_0', '12306_order_1') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '本脚本只能在 12306_order_0 或 12306_order_1 数据库中执行';
    END IF;

    WHILE table_index < 32 DO
        SET @command_table_name = CONCAT('t_order_command_', table_index);
        SET @outbox_table_name = CONCAT('t_cdc_outbox_event_', table_index);

        SELECT COUNT(*) INTO command_table_count
        FROM information_schema.tables
        WHERE table_schema = current_schema
          AND table_name = @command_table_name;
        IF command_table_count <> 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = '缺少对应 t_order_command 分表，拒绝创建无法同路由的 CDC Outbox';
        END IF;

        -- Outbox 分表与 Command 使用相同 user_id、command_id、recovery_shard 路由，保证与订单事务同库提交。
        SET @create_outbox_sql = CONCAT(
                'CREATE TABLE IF NOT EXISTS `', current_schema, '`.`', @outbox_table_name, '` (',
                '`id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT ''主键'', ',
                '`user_id` bigint(20) NOT NULL COMMENT ''订单用户路由键'', ',
                '`command_id` varchar(160) NOT NULL COMMENT ''稳定订单创建命令标识'', ',
                '`recovery_shard` tinyint unsigned NOT NULL COMMENT ''与 Command 一致的恢复分片'', ',
                '`namespace` varchar(64) NOT NULL COMMENT ''事件业务域'', ',
                '`event_id` varchar(128) NOT NULL COMMENT ''稳定事件标识'', ',
                '`event_type` varchar(64) NOT NULL COMMENT ''稳定事件类型'', ',
                '`aggregate_id` varchar(128) NOT NULL COMMENT ''业务聚合标识'', ',
                '`deduplication_key` varchar(160) NOT NULL COMMENT ''业务幂等键'', ',
                '`message_key` varchar(160) NOT NULL COMMENT ''RocketMQ 消息键'', ',
                '`destination_topic` varchar(128) NOT NULL COMMENT ''目标 Topic'', ',
                '`destination_tag` varchar(64) NOT NULL COMMENT ''目标 Tag'', ',
                '`delay_level` int unsigned NOT NULL DEFAULT 0 COMMENT ''RocketMQ 延迟级别，0 表示普通消息'', ',
                '`payload` mediumtext NOT NULL COMMENT ''不可变消息载荷 JSON'', ',
                '`event_version` bigint unsigned NOT NULL COMMENT ''事件协议版本'', ',
                '`created_at` datetime(3) NOT NULL COMMENT ''事件创建时间'', ',
                'PRIMARY KEY (`id`), ',
                'UNIQUE KEY `uk_cdc_outbox_event` (`namespace`, `event_id`), ',
                'UNIQUE KEY `uk_cdc_outbox_dedupe` (`namespace`, `deduplication_key`), ',
                'KEY `idx_cdc_outbox_command` (`user_id`, `command_id`), ',
                'KEY `idx_cdc_outbox_created` (`created_at`, `id`)',
                ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ',
                'COMMENT=''订单 CDC 追加式事件分表'''
        );
        PREPARE create_outbox_statement FROM @create_outbox_sql;
        EXECUTE create_outbox_statement;
        DEALLOCATE PREPARE create_outbox_statement;

        SET table_index = table_index + 1;
    END WHILE;
END$$
DELIMITER ;

CALL `upgrade_order_cdc_outbox_shards`();
DROP PROCEDURE `upgrade_order_cdc_outbox_shards`;

-- 验收：当前订单库应返回 32，且每张表都有两个唯一键与两个查询索引。
SELECT COUNT(*) AS cdc_outbox_shard_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name LIKE 't_cdc_outbox_event\\_%';

SELECT table_name, COUNT(DISTINCT index_name) AS index_count
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name LIKE 't_cdc_outbox_event\\_%'
  AND index_name IN (
      'uk_cdc_outbox_event',
      'uk_cdc_outbox_dedupe',
      'idx_cdc_outbox_command',
      'idx_cdc_outbox_created'
  )
GROUP BY table_name
ORDER BY table_name;
