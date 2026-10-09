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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketCdcOutboxEventDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketSeatReservationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketCdcOutboxEventMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapReleaseResult;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.inventory.TicketInventoryRollbackService;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Date;
import java.util.Objects;

/**
 * 以 reservationId 串行释放订单持有的座位、Redis 位图和统一库存。
 *
 * <p>数据库座位释放与数据库状态在同一事务中提交；Redis 和统一库存通过 reservationId 幂等执行。</p>
 */
@Service
@RequiredArgsConstructor
public class TicketSeatReservationReleaseService {

    public static final String ORDER_CREATION_EVENT_NAMESPACE = "ticket-order-creation";
    public static final String ORDER_CREATION_EVENT_TYPE = "CREATE_TICKET_ORDER";
    public static final String ORDER_CREATION_PROCESSING = "PROCESSING";
    public static final String ORDER_CREATION_SUCCEEDED = "SUCCEEDED";
    public static final String ORDER_CREATION_FAILED = "FAILED";

    private static final int STEP_PENDING = 0;
    private static final int STEP_DONE = 1;
    private static final int STEP_OWNER_CHANGED = 2;
    public static final int RESERVATION_INIT = -2;
    /** 仅兼容滚动发布前已落库的 Redis 预占记录；新链路不再写入该状态。 */
    public static final int RESERVATION_REDIS_HELD_LEGACY = -1;
    public static final int RESERVATION_PREPARED = 0;
    public static final int RESERVATION_BOUND = 1;
    public static final int RESERVATION_RELEASING = 2;
    public static final int RESERVATION_RELEASED = 3;
    public static final int RESERVATION_ABORTING = 4;
    public static final int RESERVATION_ABORTED = 5;

    private final TicketSeatReservationMapper ticketSeatReservationMapper;
    private final TicketCdcOutboxEventMapper ticketCdcOutboxEventMapper;
    private final SeatService seatService;
    private final RedisSeatBitmapService redisSeatBitmapService;
    private final TicketInventoryRollbackService ticketInventoryRollbackService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 在统一库存扣减和 Redis 选座前独立提交初始化记录。
     *
     * @param reservationId 服务端生成的座位占用标识
     * @param userId 发起购票的用户标识
     * @param username 发起购票的用户名
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param ridingDate 乘车日期
     * @param serviceDate 列车始发日期
     * @param seatType 本次购票统一使用的座位类型
     * @param seatCount 本次购票人数
     */
    public void createInitialReservation(String reservationId, String userId, String username, Long trainId,
                                         String departure, String arrival, Date ridingDate, Date serviceDate,
                                         Integer seatType, Integer seatCount) {
        Boolean created = transactionTemplate.execute(status -> {
            // INIT 先于所有外部资源操作提交，进程中断后恢复任务始终能够定位本次 reservation。
            TicketSeatReservationDO reservation = TicketSeatReservationDO.builder()
                    .reservationId(reservationId)
                    .userId(userId)
                    .username(username)
                    .reservationStatus(RESERVATION_INIT)
                    .trainId(trainId)
                    .ridingDate(ridingDate)
                    .serviceDate(serviceDate)
                    .departure(departure)
                    .arrival(arrival)
                    .seatType(seatType)
                    .seatCount(seatCount)
                    .seatPayload("[]")
                    .dbSeatReleaseStatus(STEP_DONE)
                    .redisBitmapReleaseStatus(STEP_DONE)
                    .tokenRollbackStatus(STEP_PENDING)
                    .build();
            if (ticketSeatReservationMapper.insert(reservation) != 1) {
                throw new ServiceException("创建初始化座位预订记录失败");
            }
            return true;
        });
        if (!Boolean.TRUE.equals(created)) {
            throw new ServiceException("执行初始化座位预订事务失败");
        }
    }

    /**
     * 在统一库存扣减成功后校验 INIT 尚未被超时恢复领取。
     *
     * @param reservationId 当前座位占用标识
     */
    public void ensureInitialActiveAfterInventoryDeduct(String reservationId) {
        // 普通路径只读取 INIT；恢复任务已经领取时必须立即幂等回滚刚完成的迟到库存扣减。
        TicketSeatReservationDO reservation = ticketSeatReservationMapper.selectByReservationId(reservationId);
        if (reservation != null && reservation.getReservationStatus() == RESERVATION_INIT) {
            return;
        }
        if (reservation != null && (reservation.getReservationStatus() == RESERVATION_ABORTING
                || reservation.getReservationStatus() == RESERVATION_ABORTED)) {
            // recovery 可能在 deduct 标记写入前完成过一次空回滚，此处再次执行才能覆盖跨资源竞态。
            ticketInventoryRollbackService.rollbackInitialIfNecessary(
                    String.valueOf(reservation.getTrainId()), reservation.getServiceDate(), reservation.getDeparture(),
                    reservation.getArrival(), reservation.getSeatType(), reservation.getSeatCount(), reservationId);
        }
        throw new ServiceException("初始化座位预订已失效，请重新购票");
    }

    /**
     * 在发送订单创建命令前保存座位占用快照。
     *
     * @param reservationId 服务端生成的座位占用标识
     * @param userId 发起购票的用户标识
     * @param username 发起购票的用户名
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param ridingDate 乘车日期
     * @param serviceDate 列车始发日期
     * @param seatType 本次购票统一使用的座位类型
     * @param tickets 已锁定的座位明细
     * @param orderCreateRequest 可独立重放的完整建单请求
     */
    public void prepareReservation(String reservationId, String userId, String username, Long trainId, String departure,
                                   String arrival, java.util.Date ridingDate, java.util.Date serviceDate,
                                   Integer seatType,
                                   List<TrainPurchaseTicketRespDTO> tickets,
                                   TicketOrderCreateRemoteReqDTO orderCreateRequest) {
        // PREPARED 必须从已独立提交的 INIT 转换；-1 仅用于兼容滚动发布前的历史记录。
        TicketSeatReservationDO reservation = ticketSeatReservationMapper.selectByReservationIdForUpdate(reservationId);
        if (reservation == null || (reservation.getReservationStatus() != RESERVATION_INIT
                && reservation.getReservationStatus() != RESERVATION_REDIS_HELD_LEGACY)) {
            throw new ServiceException("初始化座位预订记录状态不允许提交锁座");
        }
        if (!Objects.equals(reservation.getUserId(), userId)
                || !Objects.equals(reservation.getTrainId(), trainId)
                || !Objects.equals(reservation.getSeatCount(), tickets.size())) {
            throw new ServiceException("初始化座位预订记录与锁座结果不一致");
        }
        // 数据库锁座、车票、PREPARED 和 Outbox 在同一事务提交；回滚后仍保留外层独立提交的初始状态。
        reservation.setUsername(username);
        reservation.setRidingDate(ridingDate);
        reservation.setServiceDate(serviceDate);
        reservation.setDeparture(departure);
        reservation.setArrival(arrival);
        reservation.setSeatType(seatType);
        reservation.setSeatPayload(JSON.toJSONString(tickets));
        reservation.setDbSeatReleaseStatus(STEP_PENDING);
        reservation.setRedisBitmapReleaseStatus(STEP_PENDING);
        reservation.setTokenRollbackStatus(STEP_PENDING);
        reservation.setReservationStatus(RESERVATION_PREPARED);
        if (ticketSeatReservationMapper.updateById(reservation) != 1) {
            throw new ServiceException("提交待绑定订单座位占用记录失败");
        }
        // 在同一本地事务中追加共享建单命令，确保座位提交后 Canal 一定能够读取到该业务事实。
        TicketOrderCreateCommandMessage commandMessage = buildOrderCreateCommand(
                reservationId, orderCreateRequest);
        appendOrderCreationEvent(reservationId, commandMessage);
    }

    /**
     * 追加锁座对应的建单 CDC 事件，并校验重复写入没有绑定不同业务载荷。
     *
     * @param reservationId 当前锁座记录标识
     * @param commandMessage 已固定的完整订单创建请求
     */
    private void appendOrderCreationEvent(
            String reservationId,
            TicketOrderCreateCommandMessage commandMessage) {
        // 事件字段和消息主键共同来自本次 reservation，禁止在 Canal 重放时重新查询座位或票价。
        TicketCdcOutboxEventDO event = TicketCdcOutboxEventDO.builder()
                .namespace(ORDER_CREATION_EVENT_NAMESPACE)
                .eventId(reservationId)
                .eventType(ORDER_CREATION_EVENT_TYPE)
                .aggregateId(reservationId)
                .deduplicationKey(reservationId)
                .messageKey(reservationId)
                .destinationTopic(TicketOrderMessageConstants.CREATE_ORDER_TOPIC)
                .destinationTag(TicketOrderMessageConstants.CREATE_ORDER_TAG)
                .delayLevel(0)
                .payload(JSON.toJSONString(commandMessage))
                .eventVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .createdAt(new Date())
                .build();
        // 唯一键解决跨实例重复请求；零行插入必须继续核对已有记录，不能把不同载荷误判为同一命令。
        if (ticketCdcOutboxEventMapper.insertIgnore(event) == 0) {
            TicketCdcOutboxEventDO existing = ticketCdcOutboxEventMapper
                    .selectByNamespaceAndDeduplicationKey(event.getNamespace(), event.getDeduplicationKey());
            if (!sameOrderCreationEvent(existing, event)) {
                throw new ServiceException("订单创建 CDC Outbox 幂等键绑定了不同事件");
            }
        }
    }

    /**
     * 判断同一建单幂等键是否仍指向完全相同的不可变事件。
     *
     * @param existing 数据库中已提交的事件
     * @param expected 本次事务希望提交的事件
     * @return 所有影响投递和业务语义的字段一致时返回 true
     */
    private boolean sameOrderCreationEvent(TicketCdcOutboxEventDO existing, TicketCdcOutboxEventDO expected) {
        // 只比较 CDC 和消息路由依赖的不可变字段，创建时间和数据库主键不参与幂等判断。
        return existing != null
                && Objects.equals(existing.getNamespace(), expected.getNamespace())
                && Objects.equals(existing.getEventId(), expected.getEventId())
                && Objects.equals(existing.getEventType(), expected.getEventType())
                && Objects.equals(existing.getAggregateId(), expected.getAggregateId())
                && Objects.equals(existing.getDeduplicationKey(), expected.getDeduplicationKey())
                && Objects.equals(existing.getMessageKey(), expected.getMessageKey())
                && Objects.equals(existing.getDestinationTopic(), expected.getDestinationTopic())
                && Objects.equals(existing.getDestinationTag(), expected.getDestinationTag())
                && Objects.equals(existing.getDelayLevel(), expected.getDelayLevel())
                && Objects.equals(existing.getPayload(), expected.getPayload())
                && Objects.equals(existing.getEventVersion(), expected.getEventVersion());
    }

    /**
     * 将票务领域的已固定建单快照转换为共享 MQ 命令，避免订单服务依赖票务内部 DTO。
     *
     * @param reservationId 购票受理标识
     * @param source 已完成乘车人和座位填充的票务建单快照
     * @return 可独立重放的订单创建命令
     */
    private TicketOrderCreateCommandMessage buildOrderCreateCommand(
            String reservationId,
            TicketOrderCreateRemoteReqDTO source) {
        if (source == null || source.getTicketOrderItems() == null || source.getTicketOrderItems().isEmpty()) {
            throw new ServiceException("订单创建命令缺少车票明细");
        }
        // 明细字段仅从锁座事务已经固定的快照复制，重试时不重新查询乘车人、票价或座位。
        List<TicketOrderCreateItem> items = source.getTicketOrderItems().stream()
                .map(item -> TicketOrderCreateItem.builder()
                        .carriageNumber(item.getCarriageNumber())
                        .seatType(item.getSeatType())
                        .seatNumber(item.getSeatNumber())
                        .realName(item.getRealName())
                        .idType(item.getIdType())
                        .idCard(item.getIdCard())
                        .phone(item.getPhone())
                        .amount(item.getAmount())
                        .ticketType(item.getTicketType())
                        .build())
                .toList();
        TicketOrderCreateRequest request = TicketOrderCreateRequest.builder()
                .userId(source.getUserId())
                .username(source.getUsername())
                .trainId(source.getTrainId())
                .departure(source.getDeparture())
                .arrival(source.getArrival())
                .source(source.getSource())
                .orderTime(source.getOrderTime())
                .ridingDate(source.getRidingDate())
                .trainNumber(source.getTrainNumber())
                .departureTime(source.getDepartureTime())
                .arrivalTime(source.getArrivalTime())
                .ticketOrderItems(items)
                .build();
        // reservationId 同时作为订单创建幂等键和最终订单号，重放不会生成新的业务标识。
        return TicketOrderCreateCommandMessage.builder()
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .reservationId(reservationId)
                .request(request)
                .build();
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
            case RESERVATION_RELEASING, RESERVATION_RELEASED, RESERVATION_ABORTING, RESERVATION_ABORTED -> ORDER_CREATION_FAILED;
            default -> ORDER_CREATION_PROCESSING;
        };
        // 只有已绑定状态对外返回订单号，中间态不暴露内部失败信息。
        return TicketPurchaseStatusRespDTO.builder()
                .reservationId(reservationId)
                .status(status)
                .orderSn(RESERVATION_BOUND == reservation.getReservationStatus() ? reservationId : null)
                .build();
    }

    /**
     * 将已创建订单幂等绑定到 PREPARED reservation。
     *
     * @param reservationId 座位占用标识
     */
    public void bindOrder(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new ServiceException("绑定订单的 reservationId 不能为空");
        }
        Boolean bound = transactionTemplate.execute(status -> {
            // 行锁串行化正常返回、超时对账和重复回调，避免同一 reservation 绑定不同订单。
            TicketSeatReservationDO reservation = ticketSeatReservationMapper
                    .selectByReservationIdForUpdate(reservationId);
            if (reservation == null) {
                throw new ServiceException("待绑定订单座位占用记录不存在");
            }
            if (reservation.getReservationStatus() == RESERVATION_BOUND) {
                return true;
            }
            if (reservation.getReservationStatus() != RESERVATION_PREPARED) {
                throw new ServiceException("座位占用记录状态不允许绑定订单");
            }
            // reservationId 已确定为最终订单号，状态转换本身就是绑定完成的唯一事实。
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
     * 释放尚未提交建单 Outbox 的 INIT reservation，并兼容回收历史 REDIS_HELD 记录。
     *
     * @param reservationId 待中止的座位占用标识
     */
    public void releaseUnpreparedReservation(String reservationId) {
        // 只领取 PREPARED 之前的状态；正常请求已经提交 PREPARED 时，本方法必须无操作返回。
        TicketSeatReservationDO reservation = claimUnpreparedRelease(reservationId);
        if (reservation == null) {
            return;
        }
        if (reservation.getRedisBitmapReleaseStatus() == STEP_PENDING) {
            // 历史 REDIS_HELD 已保存完整座位快照，复用三资源幂等释放链路。
            releaseReservation(reservation);
        } else {
            // INIT 尚未选择座位，不访问数据库座位和 Redis 位图，只回滚可能已经扣减的统一库存。
            rollbackInitialInventory(reservation);
        }
        // 独立的 ABORTED 终态避免后续 PREPARED 命令恢复把本地预备失败误当成建单失败。
        markUnpreparedReleased(reservationId);
    }

    /**
     * 推进已关闭订单的所有 reservation 释放步骤。
     *
     * @param orderSn 已关闭订单号，同时也是座位预订标识
     */
    public void releaseOrder(String orderSn) {
        // 新协议订单号就是 reservationId，按预订记录读取资源快照而不是信任关闭消息中的座位字段。
        TicketSeatReservationDO reservation = ticketSeatReservationMapper.selectByReservationId(orderSn);
        if (reservation != null) {
            if (reservation.getReservationStatus() != RESERVATION_BOUND) {
                throw new ServiceException("订单不存在可释放的座位占用记录");
            }
            releaseReservation(reservation);
            return;
        }
        // 灰度期间历史订单号与 reservationId 不相等，保留回退读取以避免延迟关单遗漏释放。
        List<TicketSeatReservationDO> legacyReservations = ticketSeatReservationMapper.selectLegacyBoundByOrderSn(orderSn);
        if (legacyReservations.isEmpty()) {
            throw new ServiceException("订单不存在可释放的座位占用记录");
        }
        legacyReservations.forEach(this::releaseReservation);
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
     * 原子领取 PREPARED 之前的失败补偿权，并阻止原请求继续提交锁座。
     *
     * @param reservationId 座位占用标识
     * @return 需要继续释放的记录；记录已进入 PREPARED 或已结束时返回 null
     */
    private TicketSeatReservationDO claimUnpreparedRelease(String reservationId) {
        return transactionTemplate.execute(status -> {
            // 与 prepareReservation 共用同一行锁，INIT 和 PREPARED 只能有一个分支先推进。
            TicketSeatReservationDO reservation = ticketSeatReservationMapper.selectByReservationIdForUpdate(reservationId);
            if (reservation == null
                    || reservation.getReservationStatus() == RESERVATION_PREPARED
                    || reservation.getReservationStatus() == RESERVATION_BOUND
                    || reservation.getReservationStatus() == RESERVATION_RELEASING
                    || reservation.getReservationStatus() == RESERVATION_RELEASED
                    || reservation.getReservationStatus() == RESERVATION_ABORTED) {
                return null;
            }
            if (reservation.getReservationStatus() != RESERVATION_INIT
                    && reservation.getReservationStatus() != RESERVATION_REDIS_HELD_LEGACY
                    && reservation.getReservationStatus() != RESERVATION_ABORTING) {
                throw new ServiceException("座位预订记录状态不允许执行预备失败释放");
            }
            if (reservation.getReservationStatus() != RESERVATION_ABORTING) {
                // ABORTING 是 fencing 状态，写入后迟到的正常请求不能再转换为 PREPARED。
                reservation.setReservationStatus(RESERVATION_ABORTING);
                if (ticketSeatReservationMapper.updateById(reservation) != 1) {
                    throw new ServiceException("领取预备失败释放权失败");
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
            // 仅当数据库、位图和统一库存均完成后进入 RELEASED，扫描器才会停止重试。
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
     * 在预备阶段涉及的资源均已释放后写入 ABORTED 终态。
     *
     * @param reservationId 座位占用标识
     */
    private void markUnpreparedReleased(String reservationId) {
        Boolean released = transactionTemplate.execute(status -> {
            // 再次读取最新步骤状态，确保补偿异常时保留 ABORTING 供定时任务重试。
            TicketSeatReservationDO reservation = ticketSeatReservationMapper.selectByReservationIdForUpdate(reservationId);
            if (reservation == null) {
                throw new ServiceException("待中止的座位预订记录不存在");
            }
            if (reservation.getReservationStatus() == RESERVATION_ABORTED) {
                return true;
            }
            if (reservation.getReservationStatus() != RESERVATION_ABORTING) {
                throw new ServiceException("座位预订记录状态不允许结束预备失败释放");
            }
            if (!isReleaseStepCompleted(reservation.getDbSeatReleaseStatus())
                    || !isReleaseStepCompleted(reservation.getRedisBitmapReleaseStatus())
                    || !isReleaseStepCompleted(reservation.getTokenRollbackStatus())) {
                return false;
            }
            // ABORTED 明确表示尚未产生可靠建单命令，后续扫描不再访问订单服务。
            reservation.setReservationStatus(RESERVATION_ABORTED);
            if (ticketSeatReservationMapper.updateById(reservation) != 1) {
                throw new ServiceException("标记预备失败座位预订记录失败");
            }
            return true;
        });
        if (!Boolean.TRUE.equals(released)) {
            throw new ServiceException("预备失败座位预订记录仍有资源步骤未完成");
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
     * 推进单个 reservation 的数据库座位、Redis 位图和统一库存释放。
     *
     * @param reservation 座位占用快照
     */
    private void releaseReservation(TicketSeatReservationDO reservation) {
        // 先在本地事务内完成数据库座位释放和步骤状态更新，提交后新订单才可能获得该座位。
        TicketSeatReservationDO latest = releaseDatabaseSeat(reservation.getReservationId());
        List<TrainPurchaseTicketRespDTO> tickets = deserializeTickets(latest);

        // Redis owner 校验只允许释放本 reservation 的临时位图，owner 已变化时保留新用户的位图。
        releaseRedisBitmap(latest, tickets);

        // 统一库存使用 reservationId 去重，Redis 成功而数据库状态未写入时重试也不会多回补库存。
        rollbackInventory(latest, tickets);
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
                reservation.getSeatType(), tickets, reservation.getReservationId());

        // owner 已变更不是可重试的异常；旧 reservation 无权继续清理当前位图。
        reservation.setRedisBitmapReleaseStatus(result == RedisSeatBitmapReleaseResult.OWNER_CHANGED
                ? STEP_OWNER_CHANGED : STEP_DONE);
        if (ticketSeatReservationMapper.updateById(reservation) != 1) {
            throw new ServiceException("更新 Redis 位图释放状态失败");
        }
    }

    /**
     * 回滚 reservation 已扣除的统一库存资源并写回完成状态。
     *
     * @param reservation 当前座位占用记录
     * @param tickets 当前 reservation 持有的座位明细
     */
    private void rollbackInventory(TicketSeatReservationDO reservation, List<TrainPurchaseTicketRespDTO> tickets) {
        // 库存回滚状态已完成时直接返回，避免数据库正常状态下重复调用 Lua。
        if (reservation.getTokenRollbackStatus() == STEP_DONE) {
            return;
        }
        // 统一库存和车厢摘要使用 reservationId 作为幂等键，覆盖关闭消息重复投递与进程重启。
        ticketInventoryRollbackService.rollbackIfNecessary(
                String.valueOf(reservation.getTrainId()), reservation.getServiceDate(), reservation.getDeparture(), reservation.getArrival(),
                reservation.getSeatType(), reservation.getSeatCount(), tickets, reservation.getReservationId());
        reservation.setTokenRollbackStatus(STEP_DONE);
        if (ticketSeatReservationMapper.updateById(reservation) != 1) {
            throw new ServiceException("更新统一库存回滚状态失败");
        }
    }

    /**
     * 回滚 INIT 阶段可能已经扣减的统一库存，并记录幂等步骤状态。
     *
     * @param reservation 尚未产生具体座位的初始化预订记录
     */
    private void rollbackInitialInventory(TicketSeatReservationDO reservation) {
        if (reservation.getTokenRollbackStatus() == STEP_DONE) {
            return;
        }
        // 统一库存 Lua 会先检查 deduct 标记；进程在扣减前中断时调用同样安全且不会凭空加票。
        ticketInventoryRollbackService.rollbackInitialIfNecessary(
                String.valueOf(reservation.getTrainId()), reservation.getServiceDate(), reservation.getDeparture(),
                reservation.getArrival(), reservation.getSeatType(), reservation.getSeatCount(), reservation.getReservationId());
        reservation.setTokenRollbackStatus(STEP_DONE);
        if (ticketSeatReservationMapper.updateById(reservation) != 1) {
            throw new ServiceException("更新初始化预订库存回滚状态失败");
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

}
