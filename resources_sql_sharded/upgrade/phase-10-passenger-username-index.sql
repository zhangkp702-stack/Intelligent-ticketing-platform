-- 乘车人用户名查询索引升级脚本。
-- 请分别在 12306_user_0 和 12306_user_1 数据库中执行一次。
-- 脚本只处理当前库实际存在的乘车人分表，并可重复执行。

DROP PROCEDURE IF EXISTS upgrade_passenger_username_index;

DELIMITER $$

CREATE PROCEDURE upgrade_passenger_username_index()
BEGIN
    DECLARE current_passenger_table VARCHAR(64);
    DECLARE username_index_exists INT DEFAULT 0;
    DECLARE done TINYINT DEFAULT 0;
    DECLARE passenger_table_cursor CURSOR FOR
        SELECT TABLE_NAME
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME REGEXP '^t_passenger_[0-9]+$';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    -- 每个分库只扫描实际存在的乘车人分表，避免依赖固定分片数量。
    OPEN passenger_table_cursor;
    read_passenger_table_loop: LOOP
        FETCH passenger_table_cursor INTO current_passenger_table;
        IF done = 1 THEN
            LEAVE read_passenger_table_loop;
        END IF;

        -- 任意以 username 为首列的索引都能支持当前等值分片查询，无需重复建索引。
        SELECT COUNT(*) INTO username_index_exists
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = current_passenger_table
          AND COLUMN_NAME = 'username'
          AND SEQ_IN_INDEX = 1;
        IF username_index_exists = 0 THEN
            SET @alter_passenger_sql = CONCAT(
                    'ALTER TABLE ', current_passenger_table,
                    ' ADD INDEX idx_username (username)'
                                       );
            PREPARE alter_passenger_statement FROM @alter_passenger_sql;
            EXECUTE alter_passenger_statement;
            DEALLOCATE PREPARE alter_passenger_statement;
        END IF;
    END LOOP;
    CLOSE passenger_table_cursor;
END$$

DELIMITER ;

CALL upgrade_passenger_username_index();
DROP PROCEDURE upgrade_passenger_username_index;
