-- 主要城市高铁线路演示数据。
-- 车次和时刻用于本地联调；票价参考公开高铁票价区间设置，不代表 12306 实时售价。
-- 票价单位为分，席别类型：0 商务座、1 一等座、2 二等座。
-- 本脚本维护 ID 为 1001 至 1019 的演示车次，并调整原始 G35、G39 的座位容量，可重复执行。

USE `12306_ticket`;
SET NAMES utf8mb4;

DROP TEMPORARY TABLE IF EXISTS `tmp_demo_train`;
CREATE TEMPORARY TABLE `tmp_demo_train`
(
    `train_id`           bigint       NOT NULL,
    `train_number`       varchar(32)  NOT NULL,
    `start_station`      varchar(64)  NOT NULL,
    `end_station`        varchar(64)  NOT NULL,
    `start_region`       varchar(64)  NOT NULL,
    `end_region`         varchar(64)  NOT NULL,
    `departure_time`     datetime     NOT NULL,
    `arrival_time`       datetime     NOT NULL,
    `first_ratio_bps`    int          NOT NULL,
    `business_ratio_bps` int          NOT NULL,
    PRIMARY KEY (`train_id`)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO `tmp_demo_train`
VALUES (1001, 'G9001', '北京南', '上海虹桥', '北京', '上海', '2026-01-01 07:00:00', '2026-01-01 11:35:00', 16872, 31609),
       (1002, 'G9003', '北京南', '上海虹桥', '北京', '上海', '2026-01-01 09:00:00', '2026-01-01 13:42:00', 16875, 31580),
       (1003, 'G9005', '北京南', '上海虹桥', '北京', '上海', '2026-01-01 14:00:00', '2026-01-01 18:55:00', 16865, 31596),
       (1004, 'G9002', '上海虹桥', '北京南', '上海', '北京', '2026-01-01 07:15:00', '2026-01-01 11:55:00', 16872, 31609),
       (1005, 'G9004', '上海虹桥', '北京南', '上海', '北京', '2026-01-01 16:00:00', '2026-01-01 20:45:00', 16875, 31580),
       (1006, 'G9101', '上海虹桥', '杭州东', '上海', '杭州', '2026-01-01 06:40:00', '2026-01-01 07:35:00', 16027, 30000),
       (1007, 'G9103', '上海虹桥', '杭州东', '上海', '杭州', '2026-01-01 10:15:00', '2026-01-01 11:08:00', 16000, 30000),
       (1008, 'G9105', '上海虹桥', '杭州东', '上海', '杭州', '2026-01-01 18:10:00', '2026-01-01 19:05:00', 15976, 30000),
       (1009, 'G9102', '杭州东', '上海虹桥', '杭州', '上海', '2026-01-01 08:00:00', '2026-01-01 08:55:00', 16027, 30000),
       (1010, 'G9104', '杭州东', '上海虹桥', '杭州', '上海', '2026-01-01 20:00:00', '2026-01-01 20:55:00', 16026, 30000),
       (1011, 'G9201', '北京西', '广州南', '北京', '广州', '2026-01-01 07:00:00', '2026-01-01 14:45:00', 16000, 34687),
       (1012, 'G9203', '北京西', '广州南', '北京', '广州', '2026-01-01 09:00:00', '2026-01-01 16:55:00', 16000, 34565),
       (1013, 'G9202', '广州南', '北京西', '广州', '北京', '2026-01-01 08:00:00', '2026-01-01 15:50:00', 16000, 34687),
       (1014, 'G9301', '北京西', '成都东', '北京', '成都', '2026-01-01 07:30:00', '2026-01-01 15:00:00', 16000, 31667),
       (1015, 'G9303', '北京西', '成都东', '北京', '成都', '2026-01-01 12:00:00', '2026-01-01 19:45:00', 16000, 31573),
       (1016, 'G9302', '成都东', '北京西', '成都', '北京', '2026-01-01 08:30:00', '2026-01-01 16:10:00', 16000, 31667),
       (1017, 'G9501', '广州南', '深圳北', '广州', '深圳', '2026-01-01 07:00:00', '2026-01-01 07:35:00', 13333, 26667),
       (1018, 'G9503', '广州南', '深圳北', '广州', '深圳', '2026-01-01 12:00:00', '2026-01-01 12:36:00', 13571, 27143),
       (1019, 'G9505', '广州南', '深圳北', '广州', '深圳', '2026-01-01 18:00:00', '2026-01-01 18:35:00', 13500, 26875);

DROP TEMPORARY TABLE IF EXISTS `tmp_demo_train_stop`;
CREATE TEMPORARY TABLE `tmp_demo_train_stop`
(
    `train_id`                bigint       NOT NULL,
    `sequence_no`             int          NOT NULL,
    `station_id`              bigint       NOT NULL,
    `station_name`            varchar(64)  NOT NULL,
    `region_name`             varchar(64)  NOT NULL,
    `arrival_time`            datetime     NOT NULL,
    `departure_time`          datetime     NOT NULL,
    `cumulative_second_price` int          NOT NULL,
    PRIMARY KEY (`train_id`, `sequence_no`)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 京沪双向：北京南、济南西、南京南、上海虹桥。
INSERT INTO `tmp_demo_train_stop`
VALUES (1001, 1, 1, '北京南', '北京', '2026-01-01 07:00:00', '2026-01-01 07:00:00', 0),
       (1001, 2, 2, '济南西', '济南', '2026-01-01 08:22:00', '2026-01-01 08:25:00', 18500),
       (1001, 3, 3, '南京南', '南京', '2026-01-01 10:28:00', '2026-01-01 10:31:00', 44350),
       (1001, 4, 101, '上海虹桥', '上海', '2026-01-01 11:35:00', '2026-01-01 11:35:00', 55300),
       (1002, 1, 1, '北京南', '北京', '2026-01-01 09:00:00', '2026-01-01 09:00:00', 0),
       (1002, 2, 2, '济南西', '济南', '2026-01-01 10:25:00', '2026-01-01 10:28:00', 19200),
       (1002, 3, 3, '南京南', '南京', '2026-01-01 12:33:00', '2026-01-01 12:36:00', 46100),
       (1002, 4, 101, '上海虹桥', '上海', '2026-01-01 13:42:00', '2026-01-01 13:42:00', 57600),
       (1003, 1, 1, '北京南', '北京', '2026-01-01 14:00:00', '2026-01-01 14:00:00', 0),
       (1003, 2, 2, '济南西', '济南', '2026-01-01 15:30:00', '2026-01-01 15:33:00', 17000),
       (1003, 3, 3, '南京南', '南京', '2026-01-01 17:45:00', '2026-01-01 17:48:00', 41700),
       (1003, 4, 101, '上海虹桥', '上海', '2026-01-01 18:55:00', '2026-01-01 18:55:00', 52000),
       (1004, 1, 101, '上海虹桥', '上海', '2026-01-01 07:15:00', '2026-01-01 07:15:00', 0),
       (1004, 2, 3, '南京南', '南京', '2026-01-01 08:22:00', '2026-01-01 08:25:00', 10950),
       (1004, 3, 2, '济南西', '济南', '2026-01-01 10:30:00', '2026-01-01 10:33:00', 36800),
       (1004, 4, 1, '北京南', '北京', '2026-01-01 11:55:00', '2026-01-01 11:55:00', 55300),
       (1005, 1, 101, '上海虹桥', '上海', '2026-01-01 16:00:00', '2026-01-01 16:00:00', 0),
       (1005, 2, 3, '南京南', '南京', '2026-01-01 17:10:00', '2026-01-01 17:13:00', 11500),
       (1005, 3, 2, '济南西', '济南', '2026-01-01 19:18:00', '2026-01-01 19:21:00', 38400),
       (1005, 4, 1, '北京南', '北京', '2026-01-01 20:45:00', '2026-01-01 20:45:00', 57600);

-- 沪杭双向：上海虹桥、嘉兴南、杭州东，不同班次采用不同浮动票价。
INSERT INTO `tmp_demo_train_stop`
VALUES (1006, 1, 101, '上海虹桥', '上海', '2026-01-01 06:40:00', '2026-01-01 06:40:00', 0),
       (1006, 2, 102, '嘉兴南', '嘉兴', '2026-01-01 07:08:00', '2026-01-01 07:10:00', 3800),
       (1006, 3, 4, '杭州东', '杭州', '2026-01-01 07:35:00', '2026-01-01 07:35:00', 7300),
       (1007, 1, 101, '上海虹桥', '上海', '2026-01-01 10:15:00', '2026-01-01 10:15:00', 0),
       (1007, 2, 102, '嘉兴南', '嘉兴', '2026-01-01 10:42:00', '2026-01-01 10:44:00', 3400),
       (1007, 3, 4, '杭州东', '杭州', '2026-01-01 11:08:00', '2026-01-01 11:08:00', 6500),
       (1008, 1, 101, '上海虹桥', '上海', '2026-01-01 18:10:00', '2026-01-01 18:10:00', 0),
       (1008, 2, 102, '嘉兴南', '嘉兴', '2026-01-01 18:38:00', '2026-01-01 18:40:00', 4300),
       (1008, 3, 4, '杭州东', '杭州', '2026-01-01 19:05:00', '2026-01-01 19:05:00', 8200),
       (1009, 1, 4, '杭州东', '杭州', '2026-01-01 08:00:00', '2026-01-01 08:00:00', 0),
       (1009, 2, 102, '嘉兴南', '嘉兴', '2026-01-01 08:25:00', '2026-01-01 08:27:00', 3500),
       (1009, 3, 101, '上海虹桥', '上海', '2026-01-01 08:55:00', '2026-01-01 08:55:00', 7300),
       (1010, 1, 4, '杭州东', '杭州', '2026-01-01 20:00:00', '2026-01-01 20:00:00', 0),
       (1010, 2, 102, '嘉兴南', '嘉兴', '2026-01-01 20:25:00', '2026-01-01 20:27:00', 3800),
       (1010, 3, 101, '上海虹桥', '上海', '2026-01-01 20:55:00', '2026-01-01 20:55:00', 7800);

-- 京广线路覆盖郑州、武汉、长沙、广州，并提供返程班次。
INSERT INTO `tmp_demo_train_stop`
VALUES (1011, 1, 103, '北京西', '北京', '2026-01-01 07:00:00', '2026-01-01 07:00:00', 0),
       (1011, 2, 104, '郑州东', '郑州', '2026-01-01 09:20:00', '2026-01-01 09:24:00', 30900),
       (1011, 3, 105, '武汉', '武汉', '2026-01-01 11:05:00', '2026-01-01 11:09:00', 52000),
       (1011, 4, 106, '长沙南', '长沙', '2026-01-01 12:25:00', '2026-01-01 12:29:00', 65000),
       (1011, 5, 107, '广州南', '广州', '2026-01-01 14:45:00', '2026-01-01 14:45:00', 86200),
       (1012, 1, 103, '北京西', '北京', '2026-01-01 09:00:00', '2026-01-01 09:00:00', 0),
       (1012, 2, 104, '郑州东', '郑州', '2026-01-01 11:25:00', '2026-01-01 11:29:00', 33000),
       (1012, 3, 105, '武汉', '武汉', '2026-01-01 13:15:00', '2026-01-01 13:19:00', 55500),
       (1012, 4, 106, '长沙南', '长沙', '2026-01-01 14:38:00', '2026-01-01 14:42:00', 69000),
       (1012, 5, 107, '广州南', '广州', '2026-01-01 16:55:00', '2026-01-01 16:55:00', 92000),
       (1013, 1, 107, '广州南', '广州', '2026-01-01 08:00:00', '2026-01-01 08:00:00', 0),
       (1013, 2, 106, '长沙南', '长沙', '2026-01-01 10:17:00', '2026-01-01 10:21:00', 21200),
       (1013, 3, 105, '武汉', '武汉', '2026-01-01 11:37:00', '2026-01-01 11:41:00', 34200),
       (1013, 4, 104, '郑州东', '郑州', '2026-01-01 13:27:00', '2026-01-01 13:31:00', 55300),
       (1013, 5, 103, '北京西', '北京', '2026-01-01 15:50:00', '2026-01-01 15:50:00', 86200);

-- 京蓉线路经郑州、西安连接成都，并提供返程班次。
INSERT INTO `tmp_demo_train_stop`
VALUES (1014, 1, 103, '北京西', '北京', '2026-01-01 07:30:00', '2026-01-01 07:30:00', 0),
       (1014, 2, 104, '郑州东', '郑州', '2026-01-01 09:50:00', '2026-01-01 09:54:00', 30900),
       (1014, 3, 109, '西安北', '西安', '2026-01-01 11:50:00', '2026-01-01 11:55:00', 51500),
       (1014, 4, 110, '成都东', '成都', '2026-01-01 15:00:00', '2026-01-01 15:00:00', 84000),
       (1015, 1, 103, '北京西', '北京', '2026-01-01 12:00:00', '2026-01-01 12:00:00', 0),
       (1015, 2, 104, '郑州东', '郑州', '2026-01-01 14:27:00', '2026-01-01 14:31:00', 32500),
       (1015, 3, 109, '西安北', '西安', '2026-01-01 16:30:00', '2026-01-01 16:35:00', 54500),
       (1015, 4, 110, '成都东', '成都', '2026-01-01 19:45:00', '2026-01-01 19:45:00', 89000),
       (1016, 1, 110, '成都东', '成都', '2026-01-01 08:30:00', '2026-01-01 08:30:00', 0),
       (1016, 2, 109, '西安北', '西安', '2026-01-01 11:35:00', '2026-01-01 11:40:00', 32500),
       (1016, 3, 104, '郑州东', '郑州', '2026-01-01 13:36:00', '2026-01-01 13:40:00', 53100),
       (1016, 4, 103, '北京西', '北京', '2026-01-01 16:10:00', '2026-01-01 16:10:00', 84000);

-- 广深线路提供早、中、晚三个班次。
INSERT INTO `tmp_demo_train_stop`
VALUES (1017, 1, 107, '广州南', '广州', '2026-01-01 07:00:00', '2026-01-01 07:00:00', 0),
       (1017, 2, 111, '虎门', '东莞', '2026-01-01 07:17:00', '2026-01-01 07:19:00', 3500),
       (1017, 3, 108, '深圳北', '深圳', '2026-01-01 07:35:00', '2026-01-01 07:35:00', 7500),
       (1018, 1, 107, '广州南', '广州', '2026-01-01 12:00:00', '2026-01-01 12:00:00', 0),
       (1018, 2, 111, '虎门', '东莞', '2026-01-01 12:17:00', '2026-01-01 12:19:00', 3300),
       (1018, 3, 108, '深圳北', '深圳', '2026-01-01 12:36:00', '2026-01-01 12:36:00', 7000),
       (1019, 1, 107, '广州南', '广州', '2026-01-01 18:00:00', '2026-01-01 18:00:00', 0),
       (1019, 2, 111, '虎门', '东莞', '2026-01-01 18:17:00', '2026-01-01 18:19:00', 3800),
       (1019, 3, 108, '深圳北', '深圳', '2026-01-01 18:35:00', '2026-01-01 18:35:00', 8000);

-- MySQL 不允许在同一条语句中重复打开一张临时表，联表查询使用只读副本。
DROP TEMPORARY TABLE IF EXISTS `tmp_demo_train_stop_pair`;
CREATE TEMPORARY TABLE `tmp_demo_train_stop_pair` LIKE `tmp_demo_train_stop`;
INSERT INTO `tmp_demo_train_stop_pair` SELECT * FROM `tmp_demo_train_stop`;

DROP TEMPORARY TABLE IF EXISTS `tmp_demo_train_stop_count`;
CREATE TEMPORARY TABLE `tmp_demo_train_stop_count`
(
    `train_id`        bigint NOT NULL,
    `max_sequence_no` int    NOT NULL,
    PRIMARY KEY (`train_id`)
);
INSERT INTO `tmp_demo_train_stop_count`
SELECT `train_id`, MAX(`sequence_no`)
FROM `tmp_demo_train_stop`
GROUP BY `train_id`;

DROP TEMPORARY TABLE IF EXISTS `tmp_demo_seat_template`;
CREATE TEMPORARY TABLE `tmp_demo_seat_template`
(
    `carriage_number` varchar(8)  NOT NULL,
    `seat_type`       int         NOT NULL,
    `seat_number`     varchar(8)  NOT NULL,
    PRIMARY KEY (`carriage_number`, `seat_type`, `seat_number`)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

DROP TEMPORARY TABLE IF EXISTS `tmp_demo_carriage_template`;
CREATE TEMPORARY TABLE `tmp_demo_carriage_template`
(
    `carriage_number` varchar(8) NOT NULL,
    `carriage_type`   int        NOT NULL,
    `seat_count`      int        NOT NULL,
    PRIMARY KEY (`carriage_number`)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 车厢数量沿用原始高铁：2 节商务座、5 节一等座、9 节二等座。
INSERT INTO `tmp_demo_carriage_template`
VALUES ('01', 0, 5),
       ('02', 1, 44), ('03', 1, 44), ('04', 1, 44), ('05', 1, 44), ('06', 1, 44),
       ('07', 2, 60), ('08', 2, 60), ('09', 2, 60), ('10', 2, 60), ('11', 2, 60),
       ('12', 2, 60), ('13', 2, 60), ('14', 2, 60), ('15', 2, 60),
       ('16', 0, 5);

DROP TEMPORARY TABLE IF EXISTS `tmp_demo_seat_row`;
CREATE TEMPORARY TABLE `tmp_demo_seat_row`
(
    `row_number` int NOT NULL,
    PRIMARY KEY (`row_number`)
);
INSERT INTO `tmp_demo_seat_row`
VALUES (1), (2), (3), (4), (5), (6), (7), (8), (9), (10), (11), (12);

-- 商务座每节 5 个座位，保持项目原始商务座布局。
INSERT INTO `tmp_demo_seat_template`
VALUES ('01', 0, '01A'), ('01', 0, '01C'), ('01', 0, '01F'), ('01', 0, '02A'), ('01', 0, '02F'),
       ('16', 0, '01A'), ('16', 0, '01C'), ('16', 0, '01F'), ('16', 0, '02A'), ('16', 0, '02F');

-- 一等座每节 11 排，每排采用 A、C、D、F 四个座位，共 44 个。
INSERT INTO `tmp_demo_seat_template`
SELECT carriage.`carriage_number`, 1, CONCAT(LPAD(seat_row.`row_number`, 2, '0'), seat_letter.`letter`)
FROM `tmp_demo_carriage_template` carriage
JOIN `tmp_demo_seat_row` seat_row ON seat_row.`row_number` <= 11
CROSS JOIN (SELECT 'A' AS `letter` UNION ALL SELECT 'C' UNION ALL SELECT 'D' UNION ALL SELECT 'F') seat_letter
WHERE carriage.`carriage_type` = 1;

-- 二等座每节 12 排，每排采用 A、B、C、D、F 五个座位，共 60 个。
INSERT INTO `tmp_demo_seat_template`
SELECT carriage.`carriage_number`, 2, CONCAT(LPAD(seat_row.`row_number`, 2, '0'), seat_letter.`letter`)
FROM `tmp_demo_carriage_template` carriage
JOIN `tmp_demo_seat_row` seat_row ON seat_row.`row_number` <= 12
CROSS JOIN (SELECT 'A' AS `letter` UNION ALL SELECT 'B' UNION ALL SELECT 'C' UNION ALL SELECT 'D' UNION ALL SELECT 'F') seat_letter
WHERE carriage.`carriage_type` = 2;

START TRANSACTION;

-- 仅删除本脚本维护的演示车次数据，保证重复执行时不会产生重复记录。
DELETE FROM `t_seat` WHERE `train_id` BETWEEN 1001 AND 1019;
DELETE FROM `t_carriage` WHERE `train_id` BETWEEN 1001 AND 1019;
DELETE FROM `t_train_station_price` WHERE `train_id` BETWEEN 1001 AND 1019;
DELETE FROM `t_train_station_relation` WHERE `train_id` BETWEEN 1001 AND 1019;
DELETE FROM `t_train_station` WHERE `train_id` BETWEEN 1001 AND 1019;
DELETE FROM `t_train` WHERE `id` BETWEEN 1001 AND 1019;

-- 原始 G35、G39 保留既有座位主键和占用位图，只移除未出票、未占用的二等座第 13 至 18 排。
DELETE seat
FROM `t_seat` seat
WHERE seat.`train_id` IN (1, 2)
  AND seat.`seat_type` = 2
  AND CAST(LEFT(seat.`seat_number`, 2) AS UNSIGNED) > 12
  AND seat.`occupy_bitmap` = 0
  AND NOT EXISTS (
      SELECT 1
      FROM `t_ticket` ticket
      WHERE ticket.`train_id` = seat.`train_id`
        AND ticket.`carriage_number` = seat.`carriage_number`
        AND ticket.`seat_number` = seat.`seat_number`
        AND ticket.`del_flag` = 0
  );

-- 补齐主要城市和车站，固定高位 ID 避免与项目原始种子数据冲突。
INSERT INTO `t_region` (`id`, `name`, `full_name`, `code`, `initial`, `spell`, `popular_flag`, `create_time`, `update_time`, `del_flag`)
VALUES (101, '上海', '上海市', 'SHH', 'S', 'shanghai', 1, NOW(), NOW(), 0),
       (102, '郑州', '河南省郑州市', 'ZZF', 'Z', 'zhengzhou', 1, NOW(), NOW(), 0),
       (103, '武汉', '湖北省武汉市', 'WHN', 'W', 'wuhan', 1, NOW(), NOW(), 0),
       (104, '长沙', '湖南省长沙市', 'CSQ', 'C', 'changsha', 1, NOW(), NOW(), 0),
       (105, '广州', '广东省广州市', 'GZQ', 'G', 'guangzhou', 1, NOW(), NOW(), 0),
       (106, '深圳', '广东省深圳市', 'SZQ', 'S', 'shenzhen', 1, NOW(), NOW(), 0),
       (107, '西安', '陕西省西安市', 'XAY', 'X', 'xian', 1, NOW(), NOW(), 0),
       (108, '成都', '四川省成都市', 'CDW', 'C', 'chengdu', 1, NOW(), NOW(), 0),
       (109, '东莞', '广东省东莞市', 'DGQ', 'D', 'dongguan', 1, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `full_name` = VALUES(`full_name`), `code` = VALUES(`code`),
                        `initial` = VALUES(`initial`), `spell` = VALUES(`spell`),
                        `popular_flag` = VALUES(`popular_flag`), `update_time` = NOW(), `del_flag` = 0;

INSERT INTO `t_station` (`id`, `code`, `name`, `spell`, `region`, `region_name`, `create_time`, `update_time`, `del_flag`)
VALUES (101, 'AOH', '上海虹桥', 'shanghaihongqiao', 'SHH', '上海', NOW(), NOW(), 0),
       (102, 'EPH', '嘉兴南', 'jiaxingnan', 'JXH', '嘉兴', NOW(), NOW(), 0),
       (103, 'BXP', '北京西', 'beijingxi', 'BJP', '北京', NOW(), NOW(), 0),
       (104, 'ZAF', '郑州东', 'zhengzhoudong', 'ZZF', '郑州', NOW(), NOW(), 0),
       (105, 'WHN', '武汉', 'wuhan', 'WHN', '武汉', NOW(), NOW(), 0),
       (106, 'CWQ', '长沙南', 'changshanan', 'CSQ', '长沙', NOW(), NOW(), 0),
       (107, 'IZQ', '广州南', 'guangzhounan', 'GZQ', '广州', NOW(), NOW(), 0),
       (108, 'IOQ', '深圳北', 'shenzhenbei', 'SZQ', '深圳', NOW(), NOW(), 0),
       (109, 'EAY', '西安北', 'xianbei', 'XAY', '西安', NOW(), NOW(), 0),
       (110, 'ICW', '成都东', 'chengdudong', 'CDW', '成都', NOW(), NOW(), 0),
       (111, 'IUQ', '虎门', 'humen', 'DGQ', '东莞', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE `code` = VALUES(`code`), `name` = VALUES(`name`), `spell` = VALUES(`spell`),
                        `region` = VALUES(`region`), `region_name` = VALUES(`region_name`),
                        `update_time` = NOW(), `del_flag` = 0;

-- 建立可售高铁车次，车次编号使用 G9xxx 演示号，避免与真实运行图混淆。
INSERT INTO `t_train` (`id`, `train_number`, `train_type`, `train_tag`, `train_brand`, `start_station`, `end_station`,
                       `start_region`, `end_region`, `sale_time`, `sale_status`, `departure_time`, `arrival_time`,
                       `create_time`, `update_time`, `del_flag`)
SELECT `train_id`, `train_number`, 0, '0,1', '0,6', `start_station`, `end_station`, `start_region`, `end_region`,
       '2025-12-01 08:00:00', 0, `departure_time`, `arrival_time`, NOW(), NOW(), 0
FROM `tmp_demo_train`;

-- 站点表保存完整运行顺序；终点站的下一站为空，供区间位图计算完整边界。
INSERT INTO `t_train_station` (`train_id`, `station_id`, `sequence`, `departure`, `arrival`, `start_region`,
                               `end_region`, `arrival_time`, `departure_time`, `stopover_time`,
                               `create_time`, `update_time`, `del_flag`)
SELECT current_stop.`train_id`, current_stop.`station_id`, LPAD(current_stop.`sequence_no`, 2, '0'),
       current_stop.`station_name`, next_stop.`station_name`, current_stop.`region_name`, next_stop.`region_name`,
       current_stop.`arrival_time`, current_stop.`departure_time`,
       CASE
           WHEN current_stop.`sequence_no` = 1 OR next_stop.`sequence_no` IS NULL THEN NULL
           ELSE TIMESTAMPDIFF(MINUTE, current_stop.`arrival_time`, current_stop.`departure_time`)
       END,
       NOW(), NOW(), 0
FROM `tmp_demo_train_stop` current_stop
LEFT JOIN `tmp_demo_train_stop_pair` next_stop
  ON next_stop.`train_id` = current_stop.`train_id`
 AND next_stop.`sequence_no` = current_stop.`sequence_no` + 1;

-- 为每个车次生成任意前后站组合，使中途城市也能直接查询和购票。
INSERT INTO `t_train_station_relation` (`train_id`, `departure`, `arrival`, `start_region`, `end_region`,
                                        `departure_flag`, `arrival_flag`, `departure_time`, `arrival_time`,
                                        `create_time`, `update_time`, `del_flag`)
SELECT departure_stop.`train_id`, departure_stop.`station_name`, arrival_stop.`station_name`,
       departure_stop.`region_name`, arrival_stop.`region_name`,
       IF(departure_stop.`sequence_no` = 1, 1, 0),
       IF(arrival_stop.`sequence_no` = stop_count.`max_sequence_no`, 1, 0),
       departure_stop.`departure_time`, arrival_stop.`arrival_time`, NOW(), NOW(), 0
FROM `tmp_demo_train_stop` departure_stop
JOIN `tmp_demo_train_stop_pair` arrival_stop
  ON arrival_stop.`train_id` = departure_stop.`train_id`
 AND arrival_stop.`sequence_no` > departure_stop.`sequence_no`
JOIN `tmp_demo_train_stop_count` stop_count ON stop_count.`train_id` = departure_stop.`train_id`;

-- 二等座采用预设累计票价，一等座和商务座按照各班次比例生成并四舍五入到元。
INSERT INTO `t_train_station_price` (`train_id`, `departure`, `arrival`, `seat_type`, `price`,
                                     `create_time`, `update_time`, `del_flag`)
SELECT departure_stop.`train_id`, departure_stop.`station_name`, arrival_stop.`station_name`, seat_class.`seat_type`,
       CASE seat_class.`seat_type`
           WHEN 2 THEN arrival_stop.`cumulative_second_price` - departure_stop.`cumulative_second_price`
           WHEN 1 THEN ROUND((arrival_stop.`cumulative_second_price` - departure_stop.`cumulative_second_price`)
                             * train.`first_ratio_bps` / 10000 / 100) * 100
           ELSE ROUND((arrival_stop.`cumulative_second_price` - departure_stop.`cumulative_second_price`)
                           * train.`business_ratio_bps` / 10000 / 100) * 100
       END,
       NOW(), NOW(), 0
FROM `tmp_demo_train_stop` departure_stop
JOIN `tmp_demo_train_stop_pair` arrival_stop
  ON arrival_stop.`train_id` = departure_stop.`train_id`
 AND arrival_stop.`sequence_no` > departure_stop.`sequence_no`
JOIN `tmp_demo_train` train ON train.`train_id` = departure_stop.`train_id`
CROSS JOIN (SELECT 0 AS `seat_type` UNION ALL SELECT 1 UNION ALL SELECT 2) seat_class;

-- 原始 G35、G39 沿用 16 节车厢编号，仅调整一等座和二等座的车厢容量。
UPDATE `t_carriage` carriage
JOIN `tmp_demo_carriage_template` template
  ON template.`carriage_number` = carriage.`carriage_number`
 AND template.`carriage_type` = carriage.`carriage_type`
SET carriage.`seat_count` = template.`seat_count`,
    carriage.`update_time` = NOW()
WHERE carriage.`train_id` IN (1, 2);

-- 为原始 G35、G39 补充一等座第 8 至 11 排，已存在的座位保持原主键和占用状态。
INSERT IGNORE INTO `t_seat` (`id`, `train_id`, `carriage_number`, `seat_number`, `seat_type`, `price`,
                             `occupy_bitmap`, `version`, `seat_layout_code`, `seat_feature_mask`,
                             `create_time`, `update_time`, `del_flag`)
SELECT 1800000000000000000 + train.`id` * 100000
           + CAST(seat.`carriage_number` AS UNSIGNED) * 1000
           + CAST(LEFT(seat.`seat_number`, 2) AS UNSIGNED) * 10
           + CASE RIGHT(seat.`seat_number`, 1)
                 WHEN 'A' THEN 1 WHEN 'B' THEN 2 WHEN 'C' THEN 3 WHEN 'D' THEN 4 ELSE 6
             END,
       train.`id`, seat.`carriage_number`, seat.`seat_number`, seat.`seat_type`, full_price.`price`,
       0, 0, NULL, 0, NOW(), NOW(), 0
FROM `t_train` train
CROSS JOIN `tmp_demo_seat_template` seat
JOIN `t_train_station_price` full_price
  ON full_price.`train_id` = train.`id`
 AND full_price.`departure` = train.`start_station`
 AND full_price.`arrival` = train.`end_station`
 AND full_price.`seat_type` = seat.`seat_type`
WHERE train.`id` IN (1, 2)
  AND seat.`seat_type` = 1
  AND CAST(LEFT(seat.`seat_number`, 2) AS UNSIGNED) BETWEEN 8 AND 11;

-- 每个新增车次建立完整的 16 节车厢，数量与座位明细保持一致。
INSERT INTO `t_carriage` (`train_id`, `carriage_number`, `carriage_type`, `seat_count`,
                          `create_time`, `update_time`, `del_flag`)
SELECT train.`train_id`, carriage.`carriage_number`, carriage.`carriage_type`, carriage.`seat_count`, NOW(), NOW(), 0
FROM `tmp_demo_train` train
CROSS JOIN `tmp_demo_carriage_template` carriage;

-- 座位兼容价格保存全程票价；实际区间售价仍以 t_train_station_price 为准。
INSERT INTO `t_seat` (`id`, `train_id`, `carriage_number`, `seat_number`, `seat_type`, `price`,
                      `occupy_bitmap`, `version`, `seat_layout_code`, `seat_feature_mask`,
                      `create_time`, `update_time`, `del_flag`)
SELECT 1800000000000000000 + train.`train_id` * 100000
           + CAST(seat.`carriage_number` AS UNSIGNED) * 1000
           + CAST(LEFT(seat.`seat_number`, 2) AS UNSIGNED) * 10
           + CASE RIGHT(seat.`seat_number`, 1)
                 WHEN 'A' THEN 1 WHEN 'B' THEN 2 WHEN 'C' THEN 3 WHEN 'D' THEN 4 ELSE 6
             END,
       train.`train_id`, seat.`carriage_number`, seat.`seat_number`, seat.`seat_type`, full_price.`price`,
       0, 0, NULL, 0, NOW(), NOW(), 0
FROM `tmp_demo_train` train
CROSS JOIN `tmp_demo_seat_template` seat
JOIN `t_train_station_price` full_price
  ON full_price.`train_id` = train.`train_id`
 AND full_price.`departure` = train.`start_station`
 AND full_price.`arrival` = train.`end_station`
 AND full_price.`seat_type` = seat.`seat_type`;

COMMIT;

DROP TEMPORARY TABLE IF EXISTS `tmp_demo_seat_template`;
DROP TEMPORARY TABLE IF EXISTS `tmp_demo_seat_row`;
DROP TEMPORARY TABLE IF EXISTS `tmp_demo_carriage_template`;
DROP TEMPORARY TABLE IF EXISTS `tmp_demo_train_stop_count`;
DROP TEMPORARY TABLE IF EXISTS `tmp_demo_train_stop_pair`;
DROP TEMPORARY TABLE IF EXISTS `tmp_demo_train_stop`;
DROP TEMPORARY TABLE IF EXISTS `tmp_demo_train`;

-- 执行结果：19 个新增车次每班 16 节、770 座；原始 G35、G39 同样调整为每班 770 座。
SELECT COUNT(*) AS `demo_train_count` FROM `t_train` WHERE `id` BETWEEN 1001 AND 1019;
SELECT COUNT(*) AS `demo_train_station_count` FROM `t_train_station` WHERE `train_id` BETWEEN 1001 AND 1019;
SELECT COUNT(*) AS `demo_carriage_count` FROM `t_carriage` WHERE `train_id` BETWEEN 1001 AND 1019;
SELECT COUNT(*) AS `demo_seat_count` FROM `t_seat` WHERE `train_id` BETWEEN 1001 AND 1019;
SELECT COUNT(*) AS `original_high_speed_carriage_count` FROM `t_carriage` WHERE `train_id` IN (1, 2);
SELECT COUNT(*) AS `original_high_speed_seat_count` FROM `t_seat` WHERE `train_id` IN (1, 2);
