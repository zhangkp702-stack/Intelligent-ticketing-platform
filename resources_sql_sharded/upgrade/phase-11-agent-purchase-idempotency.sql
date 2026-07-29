-- Agent 购票操作在票务库中持久化执行状态，确保多实例和请求重放场景不会重复下单。
CREATE TABLE IF NOT EXISTS `12306_ticket`.`t_business_operation`
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
