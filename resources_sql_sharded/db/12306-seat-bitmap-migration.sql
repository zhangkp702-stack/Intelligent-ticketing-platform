-- 位图化改造迁移脚本（可执行版本）
-- 目标：将 t_seat 从“按区间一座多行”迁移为“一座一行 + occupy_bitmap/version”
-- 适用场景：数据库里已经存在旧版 t_seat 数据，且一张真实座位会按 start_station/end_station 重复多行。
-- 重要说明：
--   1. 旧表 t_seat 不能只做 ALTER ADD COLUMN，否则业务查询仍会命中重复行；
--   2. 必须完成“去重建新表 + 表切换 + 唯一索引”三个步骤，购票链路才能正常工作；
--   3. 旧表会被重命名为 t_seat_interval_bak，便于回滚或核对。

USE 12306_ticket;

DROP TABLE IF EXISTS t_seat_bitmap_new;

CREATE TABLE `t_seat_bitmap_new`
(
    `id`                bigint(20) unsigned NOT NULL COMMENT 'ID',
    `train_id`          bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number`   varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '车厢号',
    `seat_number`       varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '座位号',
    `seat_type`         int(3) DEFAULT NULL COMMENT '座位类型',
    `price`             int(11) DEFAULT NULL COMMENT '兼容字段，区间价格请以 t_train_station_price 为准',
    `occupy_bitmap`     bigint(20) NOT NULL DEFAULT 0 COMMENT '区间占用位图，位1表示对应运行区段已占用',
    `version`           bigint(20) NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `seat_layout_code`  varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '座位布局编码',
    `seat_feature_mask` bigint(20) NOT NULL DEFAULT 0 COMMENT '座位特征位掩码',
    `create_time`       datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`       datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`          tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_train_carriage_seat` (`train_id`, `carriage_number`, `seat_number`, `seat_type`),
    KEY `idx_train_seat_type_carriage` (`train_id`, `seat_type`, `carriage_number`),
    KEY `idx_train_carriage_seat_no` (`train_id`, `carriage_number`, `seat_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='座位表（位图版）';

INSERT INTO `t_seat_bitmap_new`
(`id`, `train_id`, `carriage_number`, `seat_number`, `seat_type`, `price`, `occupy_bitmap`, `version`, `seat_layout_code`, `seat_feature_mask`, `create_time`, `update_time`, `del_flag`)
SELECT MIN(id)          AS id,
       train_id         AS train_id,
       carriage_number  AS carriage_number,
       seat_number      AS seat_number,
       seat_type        AS seat_type,
       MAX(price)       AS price,
       0                AS occupy_bitmap,
       0                AS version,
       NULL             AS seat_layout_code,
       0                AS seat_feature_mask,
       MIN(create_time) AS create_time,
       MAX(update_time) AS update_time,
       MIN(del_flag)    AS del_flag
FROM t_seat
GROUP BY train_id, carriage_number, seat_number, seat_type;

-- 如果已存在历史占座数据，需要在切表前根据旧表的 start_station/end_station + seat_status 回填 occupy_bitmap。
-- 当前项目的初始化数据中 seat_status 为 0，因此默认 occupy_bitmap 置 0 即可。

DROP TABLE IF EXISTS t_seat_interval_bak;
RENAME TABLE t_seat TO t_seat_interval_bak,
             t_seat_bitmap_new TO t_seat;

-- 建议切换后执行以下核对 SQL：
-- 1. 确认不存在重复物理座位
-- SELECT train_id, carriage_number, seat_number, seat_type, COUNT(*)
-- FROM t_seat
-- GROUP BY train_id, carriage_number, seat_number, seat_type
-- HAVING COUNT(*) > 1;
--
-- 2. 核对同一 train_id 的座位总数是否符合“车厢数 × 每车厢座位数”预期
-- SELECT train_id, seat_type, COUNT(*) FROM t_seat GROUP BY train_id, seat_type;
