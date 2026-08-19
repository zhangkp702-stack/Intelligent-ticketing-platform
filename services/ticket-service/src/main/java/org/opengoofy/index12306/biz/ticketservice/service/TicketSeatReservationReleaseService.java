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
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapReleaseResult;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
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

    public static final String ORDER_CREATION_EVENT_NAMESPACE = "ticket-order-creation";
    public static final String ORDER_CREATION_EVENT_TYPE = "CREATE_TICKET_ORDER";
    public static final long ORDER_CREATION_EVENT_VERSION = 1L;
    public static final String ORDER_CREATION_PROCESSING = "PROCESSING";
    public static final String ORDER_CREATION_SUCCEEDED = "SUCCEEDED";
    public static final String ORDER_CREATION_FAILED = "FAILED";

    private static final int STEP_PENDING = 0;
    private static final int STEP_DONE = 1;
    private static final int STEP_OWNER_CHANGED = 2;
    private static final int RESERVATION_PREPARED = 0;
    private static final int RESERVATION_BOUND = 1;
    private static final int RESERVATION_RELEASING = 2;
    private static final int RESERVATION_RELEASED = 3;

    private final TicketSeatReservationMapper ticketSeatReservationMapper;
    private final SeatService seatService;
    private final RedisSeatBitmapService redisSeatBitmapService;
    private final TicketAvailabilityTokenBucket ticketAvailabilityTokenBucket;
    private final TransactionTemplate transactionTemplate;
    private final ReliableEventStore reliableEventStore;

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;

    /**
     * 在调用订单服务前保存座位占用快照和稳定订单命令。
     *
     * @param reservationId 服务端生成的座位占用标识
     * @param actionId 订单创建动作标识
     * @param commandId 订单创建稳定命令标识
     * @param userId 发起购票的用户标识
     * @param username 发起购票的用户名
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param ridingDate 乘车日期
     * @param serviceDate 列车始发日期
     * @param tickets 已锁定的座位明细
     * @param orderCreateRequest 可独立重放的完整建单请求
     */
    public void prepareReservation(String reservationId, String actionId, String commandId,
                                   String userId, String username, Long trainId, String departure,
                                   String arrival, java.util.Date ridingDate, java.util.Date serviceDate,
                                   List<TrainPurchaseTicketRespDTO> tickets,
                                   TicketOrderCreateRemoteReqDTO orderCreateRequest) {
        // PREPARED 记录与数据库锁座、车票写入同事务提交，使远程调用前已经具备恢复依据。
        TicketSeatReservationDO reservation = TicketSeatReservationDO.builder()
                .reservationId(reservationId)
                .actionId(actionId)
                .commandId(commandId)
                .userId(userId)
                .username(username)
                .reservationStatus(RESERVATION_PREPARED)
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
            throw new ServiceException("创建待绑定订单座位占用记录失败");
        }
        // 在同一本地事务中写入完整建单载荷，确保座位提交后一定存在可恢复的异步任务。
        reliableEventStore.enqueue(
                new ReliableEventDefinition(
                        new ReliableEventKey(ORDER_CREATION_EVENT_NAMESPACE, reservationId),
                        commandId,
                        ORDER_CREATION_EVENT_TYPE,
                        reservationId,
                        JSON.toJSONString(orderCreateRequest),
                        ORDER_CREATION_EVENT_VERSION),
                java.time.Instant.now());
    }

    /**
     * 查询当前用户的异步建单状态。
     *
     * @param reservationId 购票受理标识
     * @return 当前建单状态与真实订单号
     */
    public TicketPurchaseStatusRespDTO queryPurchaseStatus(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new ServiceException("购票受理标识不能为空");
        }
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new ServiceException("用户未登录");
        }
        // 查询同时校验归属用户，避免通过枚举 reservationId 读取他人订单号。
        TicketSeatReservationDO reservation = ticketSeatReservationMapper
                .selectByReservationIdAndUserId(reservationId, userId);
        if (reservation == null) {
            throw new ServiceException("购票受理记录不存在");
        }
        String status = switch (reservation.getReservationStatus()) {
            case RESERVATION_BOUND -> ORDER_CREATION_SUCCEEDED;
            case RESERVATION_RELEASING, RESERVATION_RELEASED -> ORDER_CREATION_FAILED;
            default -> ORDER_CREATION_PROCESSING;
        };
        // 只有已绑定状态对外返回订单号，中间态不暴露内部失败信息。
        return TicketPurchaseStatusRespDTO.builder()
                .reservationId(reservationId)
                .status(status)
                .orderSn(RESERVATION_BOUND == reservation.getReservationStatus()
                        ? reservation.getOrderSn() : null)
                .build();
    }

    /**
     * 将订单服务返回的订单号幂等绑定到 PREPARED reservation。
     *
     * @param reservationId 座位占用标识
     * @param orderSn 订单服务返回的订单号
     */
    public void bindOrder(String reservationId, String orderSn) {
        if (reservationId == null || reservationId.isBlank() || orderSn == null || orderSn.isBlank()) {
            throw new ServiceException("绑定订单的 reservationId 和 orderSn 不能为空");
        }
        Boolean bound = transactionTemplate.execute(status -> {
            // 行锁串行化正常返回、超时对账和重复回调，避免同一 reservation 绑定不同订单。
            TicketSeatReservationDO reservation = ticketSeatReservationMapper
                    .selectByReservationIdForUpdate(reservationId);
            if (reservation == null) {
                throw new ServiceException("待绑定订单座位占用记录不存在");
            }
            if (reservation.getReservationStatus() == RESERVATION_BOUND) {
                if (!orderSn.equals(reservation.getOrderSn())) {
                    throw new ServiceException("座位占用记录已绑定其他订单");
                }
                return true;
            }
            if (reservation.getReservationStatus() != RESERVATION_PREPARED) {
                throw new ServiceException("座位占用记录状态不允许绑定订单");
            }
            // 订单号与生命周期状态在同一事务内写入，关闭回滚只能看到完整绑定关系。
            reservation.setOrderSn(orderSn);
            reservation.setReservationStatus(RESERVATION_BOUND);
            if (ticketSeatReservationMapper.updateById(reservation) != 1) {
                throw new ServiceException("绑定订单座位占用记录失败");
            }
            return true;
        });
        if (!Boolean.TRUE.equals(bound)) {
            throw new ServiceException("执行订单座位占用绑定事务失败");
        }
    }

    /**
     * 释放已由订单服务明确判定失败的 PREPARED reservation。
     *
     * @param reservationId 待释放的座位占用标识
     */
    public void releasePreparedReservation(String reservationId) {
        // 先在数据库行锁内领取释放权，避免订单绑定与失败释放同时修改同一 reservation。
        TicketSeatReservationDO reservation = claimPreparedRelease(reservationId);
        if (reservation == null) {
            return;
        }
        // 复用既有三步释放逻辑，所有 Redis 操作继续通过 reservationId owner 保证幂等。
        releaseReservation(reservation);
        // 只有三个资源步骤都推进完成后才标记终态；中途失败保留 RELEASING 供下一轮恢复重试。
        markPreparedReleased(reservationId);
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
     * 将 PREPARED reservation 原子迁移为 RELEASING，禁止后续订单绑定覆盖释放裁决。
     *
     * @param reservationId 座位占用标识
     * @return 需要继续推进释放时返回刚领取的 reservation，已完成释放时返回 null
     */
    private TicketSeatReservationDO claimPreparedRelease(String reservationId) {
        return transactionTemplate.execute(status -> {
            // 使用 FOR UPDATE 把远程成功补绑与失败释放串行化，二者只能有一个先完成状态迁移。
            TicketSeatReservationDO reservation = ticketSeatReservationMapper.selectByReservationIdForUpdate(reservationId);
            if (reservation == null) {
                throw new ServiceException("待释放订单座位占用记录不存在");
            }
            if (reservation.getReservationStatus() == RESERVATION_RELEASED) {
                return null;
            }
            if (reservation.getReservationStatus() == RESERVATION_BOUND) {
                throw new ServiceException("已绑定订单的座位占用记录不能按失败释放");
            }
            if (reservation.getReservationStatus() != RESERVATION_PREPARED
                    && reservation.getReservationStatus() != RESERVATION_RELEASING) {
                throw new ServiceException("座位占用记录状态不允许失败释放");
            }
            if (reservation.getReservationStatus() == RESERVATION_PREPARED) {
                // 提前写入 RELEASING，使订单服务迟到成功时不能再绑定并释放同一库存。
                reservation.setReservationStatus(RESERVATION_RELEASING);
                if (ticketSeatReservationMapper.updateById(reservation) != 1) {
                    throw new ServiceException("领取失败释放座位占用记录失败");
                }
            }
            return reservation;
        });
    }

    /**
     * 在三类资源步骤完成后将失败 reservation 标记为 RELEASED。
     *
     * @param reservationId 座位占用标识
     */
    private void markPreparedReleased(String reservationId) {
        Boolean released = transactionTemplate.execute(status -> {
            // 再次锁定读取各步骤最新状态，避免依据释放前的旧快照提前结束恢复。
            TicketSeatReservationDO reservation = ticketSeatReservationMapper.selectByReservationIdForUpdate(reservationId);
            if (reservation == null) {
                throw new ServiceException("待释放订单座位占用记录不存在");
            }
            if (reservation.getReservationStatus() == RESERVATION_RELEASED) {
                return true;
            }
            if (reservation.getReservationStatus() != RESERVATION_RELEASING) {
                throw new ServiceException("座位占用记录状态不允许结束失败释放");
            }
            if (!isReleaseStepCompleted(reservation.getDbSeatReleaseStatus())
                    || !isReleaseStepCompleted(reservation.getRedisBitmapReleaseStatus())
                    || !isReleaseStepCompleted(reservation.getTokenRollbackStatus())) {
                return false;
            }
            // 仅当数据库、位图和令牌均完成后进入 RELEASED，扫描器才会停止重试。
            reservation.setReservationStatus(RESERVATION_RELEASED);
            if (ticketSeatReservationMapper.updateById(reservation) != 1) {
                throw new ServiceException("标记失败释放座位占用记录失败");
            }
            return true;
        });
        if (!Boolean.TRUE.equals(released)) {
            throw new ServiceException("失败座位占用记录仍有资源步骤未完成");
        }
    }

    /**
     * 判断单个资源释放步骤是否已完成，Redis owner 已变更同样代表不能再操作新 owner。
     *
     * @param stepStatus 当前步骤状态
     * @return 已完成或 owner 已变更时返回 true
     */
    private boolean isReleaseStepCompleted(Integer stepStatus) {
        return stepStatus != null && (stepStatus == STEP_DONE || stepStatus == STEP_OWNER_CHANGED);
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
                seatService.unlock(String.valueOf(locked.getTrainId()), locked.getServiceDate(), locked.getDeparture(), locked.getArrival(),
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
                String.valueOf(reservation.getTrainId()), reservation.getServiceDate(), reservation.getDeparture(), reservation.getArrival(),
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
                buildTokenRollbackOrder(reservation, tickets), reservation.getServiceDate(), reservation.getReservationId(),
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
