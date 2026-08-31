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
 * 订单创建 Command 的持久化任务记录。
 *
 * <p>记录包含独立恢复订单创建所需的加密请求快照、执行租约和重试进度；
 * 订单表仍然是已创建订单的业务事实，Command 负责描述任务是否已经被可靠接管和执行完成。</p>
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

    /** 与 userId 路由结果一致的物理恢复分片编号。 */
    private Integer recoveryShard;

    /** 不可变订单参数的摘要。 */
    private String requestFingerprint;

    /** 票务服务 Outbox 生成的源消息标识。 */
    private String sourceMessageId;

    /** 票务服务持有座位的预留标识。 */
    private String reservationId;

    /** 订单创建消息协议版本。 */
    private Long messageVersion;

    /** 经 ShardingSphere 整列加密的可恢复订单创建请求 JSON。 */
    private String requestPayload;

    /** 加密密钥版本，用于后续轮换时选择解密配置。 */
    private Integer payloadKeyVersion;

    /** 命令状态：RECEIVED、PROCESSING、RETRY_WAIT、UNKNOWN、SUCCEEDED 或 FAILED。 */
    private String status;

    /** 已经开始执行的次数。 */
    private Integer attemptCount;

    /** 允许执行的最大次数。 */
    private Integer maxAttempts;

    /** RECEIVED 或 RETRY_WAIT 状态下一次允许认领的时间。 */
    private Date nextRetryTime;

    /** 当前执行租约所有者。 */
    private String leaseOwner;

    /** 单调递增的租约围栏令牌。 */
    private Long leaseToken;

    /** 当前执行租约到期时间。 */
    private Date leaseExpireTime;

    /** 成功创建订单时写入的订单号。 */
    private String orderSn;

    /** 失败时保存的安全摘要，不暴露底层异常正文。 */
    private String failureReason;

    /** 成功或最终失败的完成时间。 */
    private Date finishTime;

    /** 终态请求载荷允许清理的时间。 */
    private Date payloadExpireTime;

}
