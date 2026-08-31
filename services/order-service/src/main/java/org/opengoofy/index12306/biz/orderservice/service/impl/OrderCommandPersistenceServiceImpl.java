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

package org.opengoofy.index12306.biz.orderservice.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCommandStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.service.OrderCommandPersistenceService;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 将 MQ 中的订单创建消息收敛为可独立恢复的 Command 记录。
 */
@Service
@RequiredArgsConstructor
public class OrderCommandPersistenceServiceImpl implements OrderCommandPersistenceService {

    private static final int COMMAND_SHARD_COUNT = 32;
    private static final int PAYLOAD_KEY_VERSION = 1;
    private static final int MAX_ATTEMPTS = 16;

    private final OrderCommandMapper orderCommandMapper;

    /**
     * 在一个本地事务内登记 RECEIVED Command，并校验重复命令仍绑定同一请求。
     *
     * @param message 已通过 MQ 到达订单服务的订单创建消息
     * @return 新建或幂等复用的 Command 记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCommandDO persistReceivedCommand(TicketOrderCreateCommandMessage message) {
        // 持久化前完成协议和分片字段校验，避免无法恢复的坏任务进入数据库。
        validateMessage(message);
        String userId = message.getRequest().getUserId().trim();
        String requestPayload = JSON.toJSONString(message.getRequest());
        String requestFingerprint = fingerprint(requestPayload);

        // 先读取同一用户分片；正常重投无需再次写库。
        OrderCommandDO existing = findCommand(userId, message.getCommandId());
        if (existing != null) {
            return requireSameCommand(existing, message, requestFingerprint);
        }

        Date now = new Date();
        OrderCommandDO command = OrderCommandDO.builder()
                .commandId(message.getCommandId())
                .actionId(message.getActionId())
                .userId(userId)
                .recoveryShard(calculateRecoveryShard(userId))
                .requestFingerprint(requestFingerprint)
                .sourceMessageId(message.getMessageId())
                .reservationId(message.getReservationId())
                .messageVersion(message.getMessageVersion())
                .requestPayload(requestPayload)
                .payloadKeyVersion(PAYLOAD_KEY_VERSION)
                .status(OrderCommandStatusEnum.RECEIVED.name())
                .attemptCount(0)
                .maxAttempts(MAX_ATTEMPTS)
                .nextRetryTime(now)
                .leaseToken(0L)
                .build();
        try {
            // 返回成功后 MQ 才能确认消息，因而 insert 是消息接管的持久化边界。
            if (orderCommandMapper.insert(command) != 1) {
                throw new ServiceException("持久化订单创建 Command 失败");
            }
            return command;
        } catch (DuplicateKeyException exception) {
            // 并发重复投递由唯一键裁决，随后读取胜出记录并再次校验不可变字段。
            OrderCommandDO concurrent = findCommand(userId, message.getCommandId());
            if (concurrent == null) {
                throw exception;
            }
            return requireSameCommand(concurrent, message, requestFingerprint);
        }
    }

    /**
     * 校验消息包含建立持久化任务所需的稳定标识和完整请求。
     *
     * @param message 待持久化消息
     */
    private void validateMessage(TicketOrderCreateCommandMessage message) {
        if (message == null
                || message.getMessageVersion() == null
                || message.getMessageVersion() != TicketOrderMessageConstants.CREATE_ORDER_VERSION
                || !hasText(message.getMessageId())
                || !hasText(message.getReservationId())
                || !hasText(message.getActionId())
                || !hasText(message.getCommandId())
                || message.getRequest() == null
                || !hasText(message.getRequest().getUserId())
                || message.getRequest().getTicketOrderItems() == null
                || message.getRequest().getTicketOrderItems().isEmpty()) {
            throw new ServiceException("订单创建消息缺少持久化 Command 所需字段");
        }
        // 外层标识必须形成唯一映射，防止重放载荷覆盖另一笔座位预留。
        if (!Objects.equals(message.getMessageId(), message.getReservationId())
                || !Objects.equals(message.getCommandId(), message.getActionId() + ":create-order")) {
            throw new ServiceException("订单创建消息标识关系不一致");
        }
        if (message.getMessageId().length() > 128
                || message.getReservationId().length() > 64
                || message.getActionId().length() > 128
                || message.getCommandId().length() > 160) {
            throw new ServiceException("订单创建消息标识超过 Command 字段长度限制");
        }
        try {
            // userId 既是业务归属也是分片键，必须可转换为订单服务使用的 Long。
            long userId = Long.parseLong(message.getRequest().getUserId().trim());
            if (userId <= 0) {
                throw new NumberFormatException("userId must be positive");
            }
        } catch (NumberFormatException exception) {
            throw new ServiceException("订单创建消息用户标识不合法");
        }
    }

    /**
     * 按 userId 分片键读取稳定 Command。
     *
     * @param userId 用户分片键
     * @param commandId 稳定 Command 标识
     * @return 已存在记录，不存在时返回 null
     */
    private OrderCommandDO findCommand(String userId, String commandId) {
        // 查询始终携带 userId，避免 ShardingSphere 广播全部订单分片。
        return orderCommandMapper.selectOne(Wrappers.<OrderCommandDO>query()
                // 幂等校验只读取身份摘要，避免普通重投无谓解密乘客请求载荷。
                .select("command_id", "action_id", "user_id", "recovery_shard", "request_fingerprint",
                        "source_message_id", "reservation_id", "message_version", "status")
                .eq("user_id", userId)
                .eq("command_id", commandId));
    }

    /**
     * 校验重复消息与已持久化 Command 的不可变身份和请求摘要一致。
     *
     * @param existing 已存在 Command
     * @param message 当前重复消息
     * @param requestFingerprint 当前请求摘要
     * @return 可安全幂等复用的原记录
     */
    private OrderCommandDO requireSameCommand(
            OrderCommandDO existing,
            TicketOrderCreateCommandMessage message,
            String requestFingerprint) {
        if (!Objects.equals(existing.getUserId(), message.getRequest().getUserId().trim())
                || !Objects.equals(existing.getCommandId(), message.getCommandId())
                || !Objects.equals(existing.getActionId(), message.getActionId())
                || !Objects.equals(existing.getSourceMessageId(), message.getMessageId())
                || !Objects.equals(existing.getReservationId(), message.getReservationId())
                || !Objects.equals(existing.getMessageVersion(), message.getMessageVersion())
                || !Objects.equals(existing.getRecoveryShard(), calculateRecoveryShard(existing.getUserId()))
                || !Objects.equals(existing.getRequestFingerprint(), requestFingerprint)) {
            throw new ServiceException("订单 Command 标识与原始请求不一致");
        }
        return existing;
    }

    /**
     * 计算持久化请求 JSON 的 SHA-256 摘要。
     *
     * @param requestPayload 序列化后的完整请求
     * @return 十六进制请求摘要
     */
    private String fingerprint(String requestPayload) {
        try {
            // 摘要用于重复消息冲突检测，真实请求仍只保存在加密列中。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(requestPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 按现有订单分片算法计算恢复分片，供后台携带等值路由键定向扫描物理表。
     *
     * @param userId 已校验的数字用户标识
     * @return 0 到 31 的物理恢复分片编号
     */
    private int calculateRecoveryShard(String userId) {
        // 必须复用当前算法的“末六位字符串 hash”规则，确保恢复路由与正常 userId 路由落到同一物理表。
        String shardingValue = userId.substring(Math.max(userId.length() - 6, 0));
        return (int) (Math.abs((long) shardingValue.hashCode()) % COMMAND_SHARD_COUNT);
    }

    /**
     * 判断字符串是否包含有效内容。
     *
     * @param value 待检查字符串
     * @return 存在非空白字符时返回 true
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
