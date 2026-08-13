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

package org.opengoofy.index12306.biz.ticketservice.service;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketSeatReservationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapReleaseResult;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 以 reservationId 串行释放订单持有的座位、Redis 位图和令牌桶。
 *
 * <p>数据库座位释放与数据库状态在同一事务中提交；Redis 和令牌桶通过 reservationId 在 Lua 中幂等执行。</p>
 */
@Service
@RequiredArgsConstructor
public class TicketSeatReservationReleaseService {

    private static final int STEP_PENDING = 0;
    private static final int STEP_DONE = 1;
    private static final int STEP_OWNER_CHANGED = 2;

    private final TicketSeatReservationMapper ticketSeatReservationMapper;
    private final SeatService seatService;
    private final RedisSeatBitmapService redisSeatBitmapService;
    private final TicketAvailabilityTokenBucket ticketAvailabilityTokenBucket;
    private final TransactionTemplate transactionTemplate;

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;

    /**
     * 创建订单成功后保存该订单对全部座位的唯一占用关系。
     *
     * @param orderSn 订单号
     * @param reservationId 服务端生成的座位占用标识
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param ridingDate 乘车日期
     * @param serviceDate 列车始发日期
     * @param tickets 已锁定的座位明细
     */
    public void createReservation(String orderSn, String reservationId, Long trainId, String departure,
                                  String arrival, java.util.Date ridingDate, java.util.Date serviceDate,
                                  List<TrainPurchaseTicketRespDTO> tickets) {
        // 订单返回成功后，把 Redis owner 与数据库座位锁的共同归属持久化，供关闭任务恢复。
        TicketSeatReservationDO reservation = TicketSeatReservationDO.builder()
                .reservationId(reservationId)
                .orderSn(orderSn)
                .trainId(trainId)
                .ridingDate(ridingDate)
                .serviceDate(serviceDate)
                .departure(departure)
                .arrival(arrival)
                .seatPayload(JSON.toJSONString(tickets))
                .dbSeatReleaseStatus(STEP_PENDING)
                .redisBitmapReleaseStatus(STEP_PENDING)
                .tokenRollbackStatus(STEP_PENDING)
                .build();
        if (ticketSeatReservationMapper.insert(reservation) != 1) {
            throw new ServiceException("创建订单座位占用记录失败");
        }
    }

    /**
     * 推进已关闭订单的所有 reservation 释放步骤。
     *
     * @param orderSn 已关闭订单号
     */
    public void releaseOrder(String orderSn) {
        // 按订单读取持久化 reservation，而不是根据消息中的座位字段推断要释放的资源。
        List<TicketSeatReservationDO> reservations = ticketSeatReservationMapper.selectByOrderSn(orderSn);
        if (reservations.isEmpty()) {
            throw new ServiceException("订单不存在可释放的座位占用记录");
        }
        // 多乘客订单的每条 reservation 独立推进，任一失败交给可靠命令整体重试。
        reservations.forEach(this::releaseReservation);
    }

    /**
     * 推进单个 reservation 的数据库座位、Redis 位图和令牌桶释放。
     *
     * @param reservation 座位占用快照
     */
    private void releaseReservation(TicketSeatReservationDO reservation) {
        // 先在本地事务内完成数据库座位释放和步骤状态更新，提交后新订单才可能获得该座位。
        TicketSeatReservationDO latest = releaseDatabaseSeat(reservation.getReservationId());
        List<TrainPurchaseTicketRespDTO> tickets = deserializeTickets(latest);

        // Redis owner 校验只允许释放本 reservation 的临时位图，owner 已变化时保留新用户的位图。
        releaseRedisBitmap(latest, tickets);

        // 令牌桶使用 reservationId 去重，Redis 成功而数据库状态未写入时重试也不会多还令牌。
        rollbackTokenBucket(latest, tickets);
    }

    /**
     * 在同一数据库事务内释放座位并标记数据库步骤完成。
     *
     * @param reservationId 座位占用标识
     * @return 最新的座位占用记录
     */
    private TicketSeatReservationDO releaseDatabaseSeat(String reservationId) {
        TicketSeatReservationDO reservation = transactionTemplate.execute(status -> {
            // 行锁保证重复 MQ 消息只能由一个事务决定是否第一次释放数据库座位。
            TicketSeatReservationDO locked = ticketSeatReservationMapper.selectByReservationIdForUpdate(reservationId);
            if (locked == null) {
                throw new ServiceException("座位占用记录不存在");
            }
            if (locked.getDbSeatReleaseStatus() != STEP_DONE) {
                // 只按当前 reservation 记录中的座位与区间解锁，不能从迟到消息直接构造座位。
                seatService.unlock(String.valueOf(locked.getTrainId()), locked.getDeparture(), locked.getArrival(),
                        deserializeTickets(locked));
                locked.setDbSeatReleaseStatus(STEP_DONE);
                if (ticketSeatReservationMapper.updateById(locked) != 1) {
                    throw new ServiceException("更新数据库座位释放状态失败");
                }
            }
            return locked;
        });
        if (reservation == null) {
            throw new ServiceException("执行数据库座位释放事务失败");
        }
        return reservation;
    }

    /**
     * 释放 Redis 位图并将脚本结果写回 reservation 状态。
     *
     * @param reservation 当前座位占用记录
     * @param tickets 当前 reservation 持有的座位明细
     */
    private void releaseRedisBitmap(TicketSeatReservationDO reservation, List<TrainPurchaseTicketRespDTO> tickets) {
        // 已有终态时不再访问 Redis，避免重复消息接触后来订单重新占用的位图。
        if (reservation.getRedisBitmapReleaseStatus() != STEP_PENDING) {
            return;
        }
        RedisSeatBitmapReleaseResult result = redisSeatBitmapService.releaseByReservationId(
                String.valueOf(reservation.getTrainId()), reservation.getDeparture(), reservation.getArrival(),
                tickets, reservation.getReservationId());

        // owner 已变更不是可重试的异常；旧 reservation 无权继续清理当前位图。
        reservation.setRedisBitmapReleaseStatus(result == RedisSeatBitmapReleaseResult.OWNER_CHANGED
                ? STEP_OWNER_CHANGED : STEP_DONE);
        if (ticketSeatReservationMapper.updateById(reservation) != 1) {
            throw new ServiceException("更新 Redis 位图释放状态失败");
        }
    }

    /**
     * 回滚 reservation 已扣除的令牌桶资源并写回完成状态。
     *
     * @param reservation 当前座位占用记录
     * @param tickets 当前 reservation 持有的座位明细
     */
    private void rollbackTokenBucket(TicketSeatReservationDO reservation, List<TrainPurchaseTicketRespDTO> tickets) {
        // 令牌桶状态已完成时直接返回，避免数据库正常状态下重复调用 Lua。
        if (reservation.getTokenRollbackStatus() == STEP_DONE) {
            return;
        }
        // 令牌桶仍复用既有聚合算法，但唯一幂等键改为 reservationId 而非订单号。
        ticketAvailabilityTokenBucket.rollbackInBucketIfNecessary(
                buildTokenRollbackOrder(reservation, tickets), reservation.getReservationId(),
                !"binlog".equals(ticketAvailabilityCacheUpdateType));
        reservation.setTokenRollbackStatus(STEP_DONE);
        if (ticketSeatReservationMapper.updateById(reservation) != 1) {
            throw new ServiceException("更新令牌桶回滚状态失败");
        }
    }

    /**
     * 反序列化 reservation 保存的座位明细。
     *
     * @param reservation 当前座位占用记录
     * @return 可以直接用于座位与 Redis 操作的座位明细
     */
    private List<TrainPurchaseTicketRespDTO> deserializeTickets(TicketSeatReservationDO reservation) {
        // 座位明细是 reservation 的不可变快照，恢复时不依赖可能已变化的远程订单展示数据。
        List<TrainPurchaseTicketRespDTO> tickets = JSON.parseArray(reservation.getSeatPayload(), TrainPurchaseTicketRespDTO.class);
        if (tickets == null || tickets.isEmpty()) {
            throw new ServiceException("座位占用记录缺少座位明细");
        }
        return tickets;
    }

    /**
     * 将 reservation 快照转换为既有令牌桶回滚接口所需的订单明细。
     *
     * @param reservation 当前座位占用记录
     * @param tickets 当前 reservation 持有的座位明细
     * @return 仅包含令牌计算所需字段的订单明细
     */
    private TicketOrderDetailRespDTO buildTokenRollbackOrder(TicketSeatReservationDO reservation,
                                                              List<TrainPurchaseTicketRespDTO> tickets) {
        // 令牌桶只依赖车次、区间、座位类型和车厢，因此从持久化快照重建最小明细。
        TicketOrderDetailRespDTO order = new TicketOrderDetailRespDTO();
        order.setTrainId(reservation.getTrainId());
        order.setDeparture(reservation.getDeparture());
        order.setArrival(reservation.getArrival());
        order.setPassengerDetails(tickets.stream()
                .map(each -> TicketOrderPassengerDetailRespDTO.builder()
                        .seatType(each.getSeatType())
                        .carriageNumber(each.getCarriageNumber())
                        .build())
                .toList());
        return order;
    }
}
