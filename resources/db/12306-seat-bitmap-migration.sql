-- 位图化改造迁移脚本（最小可运行闭环）
-- 目标：将 t_seat 从按区间展开改为一座一行 + occupy_bitmap/version

USE 12306_ticket;

ALTER TABLE t_seat
    ADD COLUMN occupy_bitmap BIGINT NOT NULL DEFAULT 0 COMMENT '区间占用位图，位1表示对应运行区段已占用',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    ADD COLUMN seat_layout_code VARCHAR(64) NULL COMMENT '座位布局编码',
    ADD COLUMN seat_feature_mask BIGINT NOT NULL DEFAULT 0 COMMENT '座位特征位掩码';

-- 迁移策略：从旧表中抽取真实座位维度，保留一座一行。
-- 注意：该脚本假设旧数据里同一真实座位存在多条 start_station/end_station 记录。
CREATE TABLE IF NOT EXISTS t_seat_bitmap_new AS
SELECT MIN(id)            AS id,
       train_id           AS train_id,
       carriage_number    AS carriage_number,
       seat_number        AS seat_number,
       seat_type          AS seat_type,
       MAX(price)         AS price,
       0                  AS occupy_bitmap,
       0                  AS version,
       NULL               AS seat_layout_code,
       0                  AS seat_feature_mask,
       MIN(create_time)   AS create_time,
       MAX(update_time)   AS update_time,
       MIN(del_flag)      AS del_flag
FROM t_seat
GROUP BY train_id, carriage_number, seat_number, seat_type;

ALTER TABLE t_seat_bitmap_new
    ADD PRIMARY KEY (id),
    ADD KEY idx_train_seat_type_carriage (train_id, seat_type, carriage_number),
    ADD KEY idx_train_seat_no (train_id, carriage_number, seat_number);

-- 人工确认数据后执行切换：
-- RENAME TABLE t_seat TO t_seat_interval_bak, t_seat_bitmap_new TO t_seat;

-- 如果需要按旧 locked/sold 状态回填位图，可结合列车站点顺序计算 requestMask 后执行如下更新：
-- update t_seat s
-- join (...) x on s.train_id = x.train_id and s.carriage_number = x.carriage_number and s.seat_number = x.seat_number and s.seat_type = x.seat_type
-- set s.occupy_bitmap = s.occupy_bitmap | x.request_mask,
--     s.version = s.version + 1;
