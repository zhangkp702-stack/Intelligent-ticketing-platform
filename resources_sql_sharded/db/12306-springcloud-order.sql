CREATE DATABASE IF NOT EXISTS `12306_order_0` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE
12306_order_0;

CREATE TABLE `t_order_0`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_1`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_10`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_11`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_12`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_13`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_14`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_15`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_2`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_3`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_4`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_5`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_6`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_7`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_8`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_9`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE `t_order_item_0`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_1`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_10`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_11`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_12`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_13`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_14`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_15`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_2`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_3`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_4`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_5`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_6`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_7`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_8`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_9`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

CREATE TABLE `t_order_item_passenger_0`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_1`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_10`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_11`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_12`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_13`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_14`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_15`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_2`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_3`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_4`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_5`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_6`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_7`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_8`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE TABLE `t_order_item_passenger_9`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

CREATE DATABASE IF NOT EXISTS `12306_order_1` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE
12306_order_1;

CREATE TABLE `t_order_16`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_16`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_16`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_17`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_17`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_17`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_18`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_18`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_18`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_19`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_19`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_19`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_20`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_20`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_20`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_21`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_21`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_21`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_22`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_22`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_22`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_23`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_23`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_23`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_24`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_24`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_24`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_25`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_25`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_25`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_26`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_26`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_26`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_27`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_27`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_27`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_28`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_28`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_28`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_29`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_29`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_29`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_30`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_30`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_30`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';
CREATE TABLE `t_order_31`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `action_id`           varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单创建动作标识',
    `command_id`          varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'stable create order command',
    `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'immutable request SHA-256',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    KEY              `idx_order_sn` (`order_sn`) USING BTREE,
    UNIQUE KEY       `uk_order_command_id` (`command_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
CREATE TABLE `t_order_item_31`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单状态',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
CREATE TABLE `t_order_item_passenger_31`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`    varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `id_type`     int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`     varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT NULL COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY           `idx_id_card` (`id_card`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='乘车人订单关系表';

-- 订单创建稳定命令终态表与订单使用同一用户分片规则。
USE `12306_order_0`;
CREATE TABLE `t_order_command_0`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_1`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_2`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_3`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_4`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_5`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_6`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_7`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_8`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_9`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_10`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_11`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_12`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_13`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_14`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_15`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
USE `12306_order_1`;
CREATE TABLE `t_order_command_16`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_17`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_18`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_19`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_20`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_21`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_22`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_23`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_24`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_25`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_26`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_27`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_28`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_29`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_30`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
CREATE TABLE `t_order_command_31`
(
    `id`                             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `command_id`                     varchar(160) NOT NULL COMMENT '稳定订单创建命令标识',
    `action_id`                      varchar(128) NOT NULL COMMENT '订单创建动作标识',
    `user_id`                        bigint(20) NOT NULL COMMENT '用户ID',
    `request_fingerprint`            char(64) NOT NULL COMMENT '不可变请求参数摘要',
    `status`                         varchar(16) NOT NULL COMMENT 'PROCESSING、SUCCEEDED 或 FAILED',
    `order_sn`                       varchar(64) DEFAULT NULL COMMENT '成功订单号',
    `failure_reason`                 varchar(128) DEFAULT NULL COMMENT '失败原因摘要',
    `delay_close_status`             tinyint(1) DEFAULT NULL COMMENT '延迟关单消息：0待发送 1发送中 2已发送',
    `delay_close_retry_count`        int NOT NULL DEFAULT 0 COMMENT '延迟关单消息失败次数',
    `delay_close_next_retry_time`    datetime DEFAULT NULL COMMENT '下次重试或发送租约到期时间',
    `delay_close_failure_reason`     varchar(128) DEFAULT NULL COMMENT '最近一次发送失败摘要',
    `create_time`                    datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`                    datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`                       tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_command_id` (`command_id`),
    KEY `idx_order_command_user_status` (`user_id`, `status`, `update_time`),
    KEY `idx_delay_close_retry` (`delay_close_status`, `delay_close_next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单创建稳定命令终态表';
