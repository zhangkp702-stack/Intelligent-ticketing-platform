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

package org.opengoofy.index12306.biz.orderservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.framework.starter.database.base.BaseDO;

import java.util.Date;

/**
 * 订单创建稳定命令的持久化终态。
 *
 * <p>订单表只能证明订单已经创建；本表额外保存已失败和处理中状态，供上游对远程超时做安全对账。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_order_command")
public class OrderCommandDO extends BaseDO {

    /** 主键。 */
    private Long id;

    /** 稳定订单创建命令标识。 */
    private String commandId;

    /** 订单创建动作标识。 */
    private String actionId;

    /** 发起命令的用户标识，同时作为订单分片键。 */
    private String userId;

    /** 不可变订单参数的摘要。 */
    private String requestFingerprint;

    /** 命令终态：PROCESSING、SUCCEEDED 或 FAILED。 */
    private String status;

    /** 成功创建订单时写入的订单号。 */
    private String orderSn;

    /** 失败时保存的安全摘要，不暴露底层异常正文。 */
    private String failureReason;

    /** 延迟关单消息状态：0待发送、1发送中、2已发送。 */
    private Integer delayCloseStatus;

    /** 延迟关单消息已失败的次数。 */
    private Integer delayCloseRetryCount;

    /** 下次允许认领或发送中租约到期时间。 */
    private Date delayCloseNextRetryTime;

    /** 最近一次延迟关单消息失败摘要。 */
    private String delayCloseFailureReason;
}
