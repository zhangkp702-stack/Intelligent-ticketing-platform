-- 为每个订单分片增加稳定命令终态表，供 ticket-service 对 PREPARED 座位执行安全对账。
-- 本脚本需要在 phase-12 之后、发布包含 PREPARED 对账逻辑的 ticket/order 服务之前执行。

ALTER TABLE `12306_ticket`.`t_ticket_seat_reservation`
    MODIFY COLUMN `reservation_status` tinyint(1) NOT NULL DEFAULT 1
        COMMENT '生命周期：0待绑定订单 1已绑定订单 2正在失败释放 3已失败释放';

DELIMITER //

CREATE PROCEDURE `12306_order_0`.create_order_command_tables()
BEGIN
    DECLARE database_index INT DEFAULT 0;
    DECLARE table_index INT DEFAULT 0;
    WHILE database_index < 2 DO
        SET table_index = 0;
        WHILE table_index < 32 DO
            SET @create_order_command_sql = CONCAT(
                    'CREATE TABLE IF NOT EXISTS `12306_order_', database_index, '`.`t_order_command_', table_index, '` (',
                    '`id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT ''ID'', ',
                    '`command_id` varchar(160) NOT NULL COMMENT ''稳定订单创建命令标识'', ',
                    '`action_id` varchar(128) NOT NULL COMMENT ''订单创建动作标识'', ',
                    '`user_id` bigint(20) NOT NULL COMMENT ''用户ID'', ',
                    '`request_fingerprint` char(64) NOT NULL COMMENT ''不可变请求参数摘要'', ',
                    '`status` varchar(16) NOT NULL COMMENT ''PROCESSING、SUCCEEDED 或 FAILED'', ',
                    '`order_sn` varchar(64) DEFAULT NULL COMMENT ''成功订单号'', ',
                    '`failure_reason` varchar(128) DEFAULT NULL COMMENT ''失败原因摘要'', ',
                    '`create_time` datetime DEFAULT NULL COMMENT ''创建时间'', ',
                    '`update_time` datetime DEFAULT NULL COMMENT ''修改时间'', ',
                    '`del_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''删除标识'', ',
                    'PRIMARY KEY (`id`), ',
                    'UNIQUE KEY `uk_order_command_id` (`command_id`), ',
                    'KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`)',
                    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=''订单创建稳定命令终态表''');
            PREPARE create_order_command_statement FROM @create_order_command_sql;
            EXECUTE create_order_command_statement;
            DEALLOCATE PREPARE create_order_command_statement;
            SET table_index = table_index + 1;
        END WHILE;
        SET database_index = database_index + 1;
    END WHILE;
END//

DELIMITER ;

CALL `12306_order_0`.create_order_command_tables();
DROP PROCEDURE `12306_order_0`.create_order_command_tables;
