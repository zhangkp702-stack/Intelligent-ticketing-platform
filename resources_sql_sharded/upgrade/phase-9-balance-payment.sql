-- 站内余额支付升级脚本。
-- 请分别在 12306_user_0 和 12306_user_1 数据库中执行一次。
-- 金额单位统一为分，现有用户和后续注册用户默认拥有 10000 元测试余额。

DROP PROCEDURE IF EXISTS upgrade_balance_payment;

DELIMITER $$

CREATE PROCEDURE upgrade_balance_payment()
BEGIN
    DECLARE current_user_table VARCHAR(64);
    DECLARE balance_column_exists INT DEFAULT 0;
    DECLARE done TINYINT DEFAULT 0;
    DECLARE user_table_cursor CURSOR FOR
        SELECT TABLE_NAME
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME REGEXP '^t_user_[0-9]+$';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    -- 每个分库只包含部分 t_user 分表，按当前库实际存在的表扫描升级。
    OPEN user_table_cursor;
    read_user_table_loop: LOOP
        FETCH user_table_cursor INTO current_user_table;
        IF done = 1 THEN
            LEAVE read_user_table_loop;
        END IF;

        -- 旧版本 MySQL 不支持 ADD COLUMN IF NOT EXISTS，先查询元数据再执行加列。
        SELECT COUNT(*) INTO balance_column_exists
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = current_user_table
          AND COLUMN_NAME = 'balance';
        IF balance_column_exists = 0 THEN
            SET @alter_user_sql = CONCAT(
                    'ALTER TABLE ', current_user_table,
                    ' ADD COLUMN balance BIGINT NOT NULL DEFAULT 1000000 COMMENT ''账户余额，单位为分'' AFTER verify_status'
                                  );
            PREPARE alter_user_statement FROM @alter_user_sql;
            EXECUTE alter_user_statement;
            DEALLOCATE PREPARE alter_user_statement;
        END IF;

        SET @balance_flow_table = REPLACE(current_user_table, 't_user_', 't_user_balance_flow_');
        SET @create_flow_sql = CONCAT(
                'CREATE TABLE IF NOT EXISTS ', @balance_flow_table, ' (',
                'id BIGINT NOT NULL AUTO_INCREMENT COMMENT ''ID'',',
                'username VARCHAR(256) NOT NULL COMMENT ''用户名'',',
                'biz_no VARCHAR(128) NOT NULL COMMENT ''业务幂等号'',',
                'biz_type TINYINT NOT NULL COMMENT ''业务类型：0支付扣款，1退款入账'',',
                'amount BIGINT NOT NULL COMMENT ''变动金额，单位为分'',',
                'balance_before BIGINT DEFAULT NULL COMMENT ''变动前余额，单位为分'',',
                'balance_after BIGINT DEFAULT NULL COMMENT ''变动后余额，单位为分'',',
                'status TINYINT NOT NULL DEFAULT 0 COMMENT ''状态：0处理中，1成功'',',
                'create_time DATETIME DEFAULT NULL COMMENT ''创建时间'',',
                'update_time DATETIME DEFAULT NULL COMMENT ''修改时间'',',
                'del_flag TINYINT NOT NULL DEFAULT 0 COMMENT ''删除标记'',',
                'PRIMARY KEY (id),',
                'UNIQUE KEY uk_user_biz_type (username, biz_no, biz_type),',
                'KEY idx_username (username)',
                ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''用户余额流水'''
                               );
        PREPARE create_flow_statement FROM @create_flow_sql;
        EXECUTE create_flow_statement;
        DEALLOCATE PREPARE create_flow_statement;
    END LOOP;
    CLOSE user_table_cursor;
END$$

DELIMITER ;

CALL upgrade_balance_payment();
DROP PROCEDURE upgrade_balance_payment;
