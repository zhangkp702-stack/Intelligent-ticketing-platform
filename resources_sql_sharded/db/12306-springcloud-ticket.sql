CREATE DATABASE IF NOT EXISTS `12306_ticket` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE
12306_ticket;
CREATE TABLE `t_carriage`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '车厢号',
    `carriage_type`   int(3) DEFAULT NULL COMMENT '车厢类型',
    `seat_count`      int(3) DEFAULT NULL COMMENT '座位数',
    `create_time`     datetime                               DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                               DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_train_id` (`train_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=65 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='车厢表';

CREATE TABLE `t_region`
(
    `id`           bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name`         varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '地区名称',
    `full_name`    varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '地区全名',
    `code`         varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '地区编码',
    `initial`      varchar(2) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '地区首字母',
    `spell`        varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '拼音',
    `popular_flag` tinyint(1) DEFAULT NULL COMMENT '热门标识',
    `create_time`  datetime                               DEFAULT NULL COMMENT '创建时间',
    `update_time`  datetime                               DEFAULT NULL COMMENT '修改时间',
    `del_flag`     tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地区表';

CREATE TABLE `t_seat`
(
    `id`                bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
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
    UNIQUE KEY `uk_train_carriage_seat` (`train_id`, `carriage_number`, `seat_number`, `seat_type`) USING BTREE,
    KEY `idx_train_seat_type_carriage` (`train_id`, `seat_type`, `carriage_number`) USING BTREE,
    KEY `idx_train_carriage_seat_no` (`train_id`, `carriage_number`, `seat_number`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1683022080920494081 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='座位表';

CREATE TABLE `t_station`
(
    `id`          bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `code`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车站编号',
    `name`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '车站名称',
    `spell`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '拼音',
    `region`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车站地区',
    `region_name` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车站地区名称',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='车站表';

CREATE TABLE `t_ticket`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `passenger_id`    bigint(20) DEFAULT NULL COMMENT '乘车人ID',
    `ticket_status`   int(3) DEFAULT NULL COMMENT '车票状态',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1682790903965503489 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='车票表';

CREATE TABLE `t_ticket_seat_reservation`
(
    `id`                          bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `reservation_id`              varchar(64)  NOT NULL COMMENT '不可复用的座位占用标识',
    `action_id`                   varchar(128) DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`                  varchar(160) DEFAULT NULL COMMENT '订单创建稳定命令标识',
    `user_id`                     varchar(64)  DEFAULT NULL COMMENT '发起购票的用户标识',
    `username`                    varchar(256) DEFAULT NULL COMMENT '发起购票的用户名',
    `order_sn`                    varchar(128) DEFAULT NULL COMMENT '订单号，待绑定阶段为空',
    `reservation_status`          tinyint(1)   NOT NULL DEFAULT 1 COMMENT '生命周期：0待绑定订单 1已绑定订单 2正在失败释放 3已失败释放',
    `train_id`                    bigint(20)   NOT NULL COMMENT '列车ID',
    `riding_date`                 date         DEFAULT NULL COMMENT '乘车日期',
    `service_date`                date         DEFAULT NULL COMMENT '列车始发日期',
    `departure`                   varchar(64)  NOT NULL COMMENT '出发站',
    `arrival`                     varchar(64)  NOT NULL COMMENT '到达站',
    `seat_payload`                mediumtext   NOT NULL COMMENT '座位明细JSON快照',
    `db_seat_release_status`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '数据库座位释放状态：0待处理 1完成',
    `redis_bitmap_release_status` tinyint(1)   NOT NULL DEFAULT 0 COMMENT 'Redis位图释放状态：0待处理 1完成 2owner已变化',
    `token_rollback_status`       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '令牌桶回滚状态：0待处理 1完成',
    `create_time`                 datetime     DEFAULT NULL COMMENT '创建时间',
    `update_time`                 datetime     DEFAULT NULL COMMENT '修改时间',
    `del_flag`                    tinyint(1)   DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_reservation_id` (`reservation_id`),
    UNIQUE KEY `uk_command_id` (`command_id`),
    KEY `idx_order_sn` (`order_sn`),
    KEY `idx_train_service_date` (`train_id`, `service_date`),
    KEY `idx_reservation_release_recovery` (`reservation_status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单座位占用及释放状态表';

CREATE TABLE `t_business_operation`
(
    `operation_id`        varchar(64)  NOT NULL COMMENT '调用方生成的全局操作标识',
    `operation_type`      varchar(32)  NOT NULL COMMENT '业务操作类型',
    `user_id`             varchar(64)  NOT NULL COMMENT '发起操作的用户标识',
    `request_fingerprint` char(64)     NOT NULL COMMENT '不可变业务参数 SHA-256 摘要',
    `status`              tinyint(2)   NOT NULL COMMENT '0 处理中，1 已成功，2 已失败',
    `result_json`         text                  COMMENT '成功响应 JSON',
    `failure_message`     varchar(500)          COMMENT '失败原因摘要',
    `create_time`         datetime     NOT NULL COMMENT '创建时间',
    `update_time`         datetime     NOT NULL COMMENT '修改时间',
    `del_flag`            tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`operation_id`),
    KEY `idx_business_operation_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务操作幂等记录';

CREATE TABLE `t_train`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `train_type`     int(3) DEFAULT NULL COMMENT '列车类型 0：高铁 1：动车 2：普通车',
    `train_tag`      varchar(32) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '列车标签 0：复兴号 1：智能动车组 2：静音车厢 3：支持选铺',
    `train_brand`    varchar(32) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '列车品牌 0：GC-高铁/城际 1：D-动车 2：Z-直达 3：T-特快 4：K-快速 5：其他 6：复兴号 7：智能动车组',
    `start_station`  varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '起始站',
    `end_station`    varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '终点站',
    `start_region`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '起始城市',
    `end_region`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '终点城市',
    `sale_time`      datetime                                DEFAULT NULL COMMENT '销售时间',
    `sale_status`    int(3) DEFAULT NULL COMMENT '销售状态 0：可售 1：不可售 2：未知',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='列车表';

CREATE TABLE `t_reliable_outbox_event`
(
    `namespace`                 varchar(64)  NOT NULL COMMENT '事件业务域',
    `event_id`                  varchar(128) NOT NULL COMMENT '服务端稳定事件标识',
    `deduplication_key`         varchar(128) NOT NULL COMMENT '业务去重键',
    `event_type`                varchar(64)  NOT NULL COMMENT '稳定事件类型',
    `aggregate_id`              varchar(128) NOT NULL COMMENT '业务聚合标识',
    `payload`                   text         NOT NULL COMMENT '不可变建单载荷',
    `event_version`             bigint       NOT NULL COMMENT '事件协议版本',
    `status`                    varchar(32)  NOT NULL COMMENT 'PENDING PUBLISHING PUBLISHED',
    `next_publish_at`           datetime(3)  NOT NULL COMMENT '最早发布时间',
    `publish_owner`             varchar(128) DEFAULT NULL COMMENT '发布租约实例',
    `publish_lease_until`       datetime(3)  DEFAULT NULL COMMENT '发布租约截止时间',
    `publish_fencing_token`     bigint       NOT NULL DEFAULT 0 COMMENT '发布围栏令牌',
    `publish_attempt_count`     int          NOT NULL DEFAULT 0 COMMENT '发布认领次数',
    `broker_message_id`         varchar(128) DEFAULT NULL COMMENT '订单号或终态业务标识',
    `published_at`              datetime(3)  DEFAULT NULL COMMENT '完成时间',
    `failure_category`          varchar(64)  DEFAULT NULL COMMENT '最近发布失败分类',
    `failure_message`           varchar(512) DEFAULT NULL COMMENT '最近发布失败摘要',
    `created_at`                datetime(3)  NOT NULL COMMENT '创建时间',
    `updated_at`                datetime(3)  NOT NULL COMMENT '修改时间',
    PRIMARY KEY (`namespace`, `event_id`),
    UNIQUE KEY `uk_reliable_outbox_dedupe` (`namespace`, `deduplication_key`),
    KEY `idx_reliable_outbox_publish` (`namespace`, `status`, `next_publish_at`),
    KEY `idx_reliable_outbox_lease` (`namespace`, `status`, `publish_lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可靠事务 Outbox';

CREATE TABLE `t_train_station`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '车次ID',
    `station_id`     bigint(20) DEFAULT NULL COMMENT '车站ID',
    `sequence`       varchar(32) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '站点顺序',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `start_region`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '起始城市',
    `end_region`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '终点城市',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到站时间',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出站时间',
    `stopover_time`  int(3) DEFAULT NULL COMMENT '停留时间，单位分',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_train_id` (`train_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='列车站点表';

CREATE TABLE `t_train_station_price`
(
    `id`          bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `train_id`    bigint(20) DEFAULT NULL COMMENT '车次ID',
    `departure`   varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '出发站点',
    `arrival`     varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '到达站点',
    `seat_type`   int(3) DEFAULT NULL COMMENT '座位类型',
    `price`       int(11) DEFAULT NULL COMMENT '车票价格',
    `create_time` datetime                               DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                               DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_train_id` (`train_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1677692017354547201 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='列车站点价格表';

CREATE TABLE `t_train_station_relation`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '车次ID',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `start_region`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '起始城市名称',
    `end_region`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '终点城市名称',
    `departure_flag` tinyint(1) DEFAULT NULL COMMENT '始发标识',
    `arrival_flag`   tinyint(1) DEFAULT NULL COMMENT '终点标识',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_train_id` (`train_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1677689610742865921 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='列车站点关系表';
