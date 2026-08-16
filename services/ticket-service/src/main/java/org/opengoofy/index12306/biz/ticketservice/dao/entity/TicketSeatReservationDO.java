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

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.framework.starter.database.base.BaseDO;

import java.util.Date;

/**
 * 订单一次座位占用的持久化记录。
 *
 * <p>该表保存订单、座位区间与 Redis owner 的稳定关联，并分别记录数据库座位、Redis 位图和令牌桶的释放进度。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_ticket_seat_reservation")
public class TicketSeatReservationDO extends BaseDO {

    /** 主键。 */
    private Long id;

    /** 不可复用的座位占用标识，同时作为 Redis 位图 owner。 */
    private String reservationId;

    /** 订单创建动作标识，普通购票也由票务服务生成。 */
    private String actionId;

    /** 订单创建稳定命令标识，用于远程结果未知时对账。 */
    private String commandId;

    /** 发起购票的用户标识，用于恢复任务按原用户查询订单命令。 */
    private String userId;

    /** 发起购票的用户名，用于恢复任务恢复下游调用上下文。 */
    private String username;

    /** 订单号；PREPARED 阶段尚未绑定订单时为空。 */
    private String orderSn;

    /** reservation 生命周期：0 待绑定订单，1 已绑定订单，2 正在失败释放，3 已失败释放。 */
    private Integer reservationStatus;

    /** 列车标识。 */
    private Long trainId;

    /** 乘车日期。 */
    private Date ridingDate;

    /** 列车从始发站出发的日期，用于隔离不同开行日的运行库存。 */
    private Date serviceDate;

    /** 出发站。 */
    private String departure;

    /** 到达站。 */
    private String arrival;

    /** 本次占用的座位明细 JSON。 */
    private String seatPayload;

    /** 数据库座位释放状态。 */
    private Integer dbSeatReleaseStatus;

    /** Redis 位图释放状态。 */
    private Integer redisBitmapReleaseStatus;

    /** 令牌桶回滚状态。 */
    private Integer tokenRollbackStatus;
}
