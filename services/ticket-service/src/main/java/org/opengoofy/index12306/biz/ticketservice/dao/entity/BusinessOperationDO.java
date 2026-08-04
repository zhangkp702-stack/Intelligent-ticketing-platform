/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.framework.starter.database.base.BaseDO;

import java.util.Date;

/**
 * 保存跨服务业务操作的认领状态和成功结果，避免同一操作重复执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_business_operation")
public class BusinessOperationDO extends BaseDO {

    /**
     * 调用方生成的全局操作标识。
     */
    @TableId(value = "operation_id", type = IdType.INPUT)
    private String operationId;

    /**
     * 业务操作类型。
     */
    private String operationType;

    /**
     * 发起操作的用户标识。
     */
    private String userId;

    /**
     * 不包含操作标识的业务参数摘要。
     */
    private String requestFingerprint;

    /**
     * 执行状态：0 处理中，1 已成功，2 已失败，3 结果未知，4 对账中，5 人工处理。
     */
    private Integer status;

    /**
     * 成功响应 JSON，用于重复请求直接返回原结果。
     */
    private String resultJson;

    /**
     * 失败原因摘要，不保存完整异常堆栈。
     */
    private String failureMessage;

    /**
     * 当前同步执行线程所属的服务实例。
     */
    private String leaseOwner;

    /**
     * 当前执行租约截止时间。
     */
    private Date leaseUntil;

    /**
     * 每次执行权迁移时递增的隔离版本。
     */
    private Long executionEpoch;

    /**
     * 最近一次执行租约心跳时间。
     */
    private Date lastHeartbeatAt;

    /**
     * 可用于只读下游查询的订单号等安全业务引用。
     */
    private String businessReference;

    /**
     * 下一次允许执行只读对账的时间。
     */
    private Date nextReconcileAt;

    /**
     * 已执行的只读对账次数。
     */
    private Integer reconcileAttemptCount;

    /**
     * 稳定失败或未知分类。
     */
    private String failureCategory;
}
