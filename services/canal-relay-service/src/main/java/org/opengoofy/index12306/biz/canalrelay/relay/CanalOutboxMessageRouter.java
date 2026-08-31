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

package org.opengoofy.index12306.biz.canalrelay.relay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.google.protobuf.InvalidProtocolBufferException;
import org.opengoofy.index12306.biz.canalrelay.config.CanalRelayProperties;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderDelayCloseMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将目标 Outbox 的 INSERT Binlog 记录还原为可投递的订单事件。
 *
 * <p>路由器不修改来源数据库；字段校验失败会阻止当前 Canal 批次 ACK，避免损坏事件被静默跳过。</p>
 */
@Component
public class CanalOutboxMessageRouter {

    private final CanalRelayProperties properties;

    /**
     * 创建只接受当前票务 Outbox 的路由器。
     *
     * @param properties Relay 订阅与表名配置
     */
    public CanalOutboxMessageRouter(CanalRelayProperties properties) {
        this.properties = properties;
    }

    /**
     * 从一个 Canal 批次中筛选并校验订单 CDC Outbox 事件。
     *
     * @param entries Canal 返回的原始 Binlog 条目
     * @return 已通过完整性校验的稳定订单事件
     */
    public List<RelayCommand> route(List<CanalEntry.Entry> entries) {
        List<RelayCommand> commands = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return commands;
        }
        // 只处理目标库表的 ROWDATA INSERT；其他业务表和状态 UPDATE 不进入订单创建主题。
        for (CanalEntry.Entry entry : entries) {
            if (!isTargetRowData(entry)) {
                continue;
            }
            CanalEntry.RowChange rowChange = parseRowChange(entry);
            if (rowChange.getEventType() != CanalEntry.EventType.INSERT) {
                continue;
            }
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                commands.add(routeInsertRow(rowData));
            }
        }
        return List.copyOf(commands);
    }

    /**
     * 判断条目是否来自 Relay 负责的 Outbox 表。
     *
     * @param entry Canal 原始条目
     * @return 目标库表的行数据变更时返回 true
     */
    private boolean isTargetRowData(CanalEntry.Entry entry) {
        return entry != null
                && entry.getEntryType() == CanalEntry.EntryType.ROWDATA
                && entry.hasHeader()
                && properties.getSourceTablePatterns().stream().anyMatch(pattern ->
                (entry.getHeader().getSchemaName() + "." + entry.getHeader().getTableName()).matches(pattern));
    }

    /**
     * 解析 Canal 协议中的行变更数据。
     *
     * @param entry 已确认属于目标表的 Canal 条目
     * @return 可遍历 INSERT 行的变更对象
     */
    private CanalEntry.RowChange parseRowChange(CanalEntry.Entry entry) {
        try {
            return CanalEntry.RowChange.parseFrom(entry.getStoreValue());
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalStateException("Canal Outbox 行变更载荷解析失败", exception);
        }
    }

    /**
     * 校验一条 Outbox INSERT 的列值与命令载荷，并生成 Relay 内部命令。
     *
     * @param rowData INSERT 后镜像列
     * @return 与 Outbox 主键强绑定的可投递订单事件
     */
    private RelayCommand routeInsertRow(CanalEntry.RowData rowData) {
        Map<String, String> columns = new HashMap<>();
        // 订单命令必须使用 INSERT 后镜像，不能从 beforeColumns 读取旧版本或租约状态。
        for (CanalEntry.Column column : rowData.getAfterColumnsList()) {
            columns.put(column.getName(), column.getValue());
        }
        String namespace = requireColumn(columns, "namespace");
        String eventType = requireColumn(columns, "event_type");
        String eventId = requireColumn(columns, "event_id");
        String deduplicationKey = requireColumn(columns, "deduplication_key");
        String aggregateId = requireColumn(columns, "aggregate_id");
        String messageKey = requireColumn(columns, "message_key");
        long eventVersion = parsePositiveLong(requireColumn(columns, "event_version"), "event_version");
        String destinationTopic = requireColumn(columns, "destination_topic");
        String destinationTag = requireColumn(columns, "destination_tag");
        int delayLevel = parseNonNegativeInt(requireColumn(columns, "delay_level"), "delay_level");
        String payload = requireColumn(columns, "payload");
        if (TicketOrderMessageConstants.CREATE_ORDER_NAMESPACE.equals(namespace)
                && TicketOrderMessageConstants.CREATE_ORDER_EVENT_TYPE.equals(eventType)) {
            return routeOrderCreateCommand(eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                    destinationTopic, destinationTag, delayLevel, payload);
        }
        if (TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE.equals(namespace)
                && TicketOrderMessageConstants.CREATE_ORDER_RESULT_EVENT_TYPE.equals(eventType)) {
            return routeOrderCreateResult(eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                    destinationTopic, destinationTag, delayLevel, payload);
        }
        if (TicketOrderMessageConstants.DELAY_CLOSE_ORDER_NAMESPACE.equals(namespace)
                && TicketOrderMessageConstants.DELAY_CLOSE_ORDER_EVENT_TYPE.equals(eventType)) {
            return routeDelayCloseOrder(eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                    destinationTopic, destinationTag, delayLevel, payload);
        }
        throw new IllegalStateException("目标 CDC Outbox INSERT 不属于已注册事件类型");
    }

    /**
     * 还原并校验票务服务提交的订单创建命令。
     *
     * @param eventId CDC 事件标识
     * @param deduplicationKey 业务幂等键
     * @param aggregateId 锁座预留标识
     * @param messageKey RocketMQ 业务键
     * @param eventVersion 事件版本
     * @param destinationTopic 目标 Topic
     * @param destinationTag 目标 Tag
     * @param delayLevel 延迟级别
     * @param payload 完整命令 JSON
     * @return 可投递的统一 Relay 命令
     */
    private RelayCommand routeOrderCreateCommand(
            String eventId,
            String deduplicationKey,
            String aggregateId,
            String messageKey,
            long eventVersion,
            String destinationTopic,
            String destinationTag,
            int delayLevel,
            String payload) {
        // 命令载荷只能由锁座事务固定，Relay 不补齐任何票价、乘车人或座位字段。
        TicketOrderCreateCommandMessage message = parseMessage(
                payload, TicketOrderCreateCommandMessage.class, "订单创建 Outbox");
        validateCommand(message, eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                destinationTopic, destinationTag, delayLevel);
        return new RelayCommand(eventId, aggregateId, message.getCommandId(),
                destinationTopic, destinationTag, delayLevel, message);
    }

    /**
     * 还原并校验订单服务提交的订单创建终态结果。
     *
     * @param eventId CDC 事件标识
     * @param deduplicationKey 业务幂等键
     * @param aggregateId 锁座预留标识
     * @param messageKey RocketMQ 业务键
     * @param eventVersion 事件版本
     * @param destinationTopic 目标 Topic
     * @param destinationTag 目标 Tag
     * @param delayLevel 延迟级别
     * @param payload 完整结果 JSON
     * @return 可投递的统一 Relay 命令
     */
    private RelayCommand routeOrderCreateResult(
            String eventId,
            String deduplicationKey,
            String aggregateId,
            String messageKey,
            long eventVersion,
            String destinationTopic,
            String destinationTag,
            int delayLevel,
            String payload) {
        // 结果载荷已在订单终态事务内固定，Relay 只校验关联键后转发给票务服务。
        TicketOrderCreateResultMessage message = parseMessage(
                payload, TicketOrderCreateResultMessage.class, "订单创建结果 Outbox");
        validateResult(message, eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                destinationTopic, destinationTag, delayLevel);
        return new RelayCommand(eventId, aggregateId, message.getCommandId(),
                destinationTopic, destinationTag, delayLevel, message);
    }

    /**
     * 还原并校验订单服务提交的延迟关闭订单事件。
     *
     * @param eventId CDC 事件标识
     * @param deduplicationKey 业务幂等键
     * @param aggregateId 待关闭订单号
     * @param messageKey RocketMQ 业务键
     * @param eventVersion 事件版本
     * @param destinationTopic 目标 Topic
     * @param destinationTag 目标 Tag
     * @param delayLevel 延迟级别
     * @param payload 兼容既有消费者的完整消息 JSON
     * @return 可投递的统一 Relay 命令
     */
    private RelayCommand routeDelayCloseOrder(
            String eventId,
            String deduplicationKey,
            String aggregateId,
            String messageKey,
            long eventVersion,
            String destinationTopic,
            String destinationTag,
            int delayLevel,
            String payload) {
        // 延迟消息不能重新计算超时时间，只转发订单事务内固定的十分钟等级和消息快照。
        TicketOrderDelayCloseMessage message = parseMessage(
                payload, TicketOrderDelayCloseMessage.class, "延迟关单 Outbox");
        validateDelayClose(message, eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                destinationTopic, destinationTag, delayLevel);
        return new RelayCommand(eventId, aggregateId, deduplicationKey,
                destinationTopic, destinationTag, delayLevel, message);
    }

    /**
     * 反序列化不可变 Outbox 载荷，不从当前座位、票价或乘车人数据重新补齐字段。
     *
     * @param payload 票务事务写入的完整命令快照
     * @return 可直接投递给订单服务的共享命令
     */
    private <T> T parseMessage(String payload, Class<T> messageType, String eventDescription) {
        try {
            T message = JSON.parseObject(payload, messageType);
            if (message == null) {
                throw new IllegalStateException(eventDescription + "载荷为空");
            }
            return message;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(eventDescription + "载荷不是有效共享消息", exception);
        }
    }

    /**
     * 校验 Outbox 列与消息协议使用同一稳定主键，防止错误事件被投递到订单服务。
     *
     * @param message 反序列化后的订单创建命令
     * @param eventId Outbox 事件标识
     * @param deduplicationKey Outbox 业务去重键
     * @param aggregateId Outbox 聚合标识
     * @param messageKey Outbox RocketMQ 业务键
     * @param eventVersion Outbox 协议版本
     */
    private void validateCommand(
            TicketOrderCreateCommandMessage message,
            String eventId,
            String deduplicationKey,
            String aggregateId,
            String messageKey,
            long eventVersion,
            String destinationTopic,
            String destinationTag,
            int delayLevel) {
        if (message.getMessageVersion() == null
                || message.getMessageVersion() != eventVersion
                || message.getMessageVersion() != TicketOrderMessageConstants.CREATE_ORDER_VERSION
                || !eventId.equals(message.getMessageId())
                || !aggregateId.equals(message.getReservationId())
                || !deduplicationKey.equals(message.getCommandId())
                || !messageKey.equals(message.getCommandId())
                || !hasText(message.getActionId())
                || !(message.getActionId() + ":create-order").equals(message.getCommandId())
                || message.getRequest() == null
                || !hasText(message.getRequest().getUserId())
                || message.getRequest().getTicketOrderItems() == null
                || message.getRequest().getTicketOrderItems().isEmpty()
                || !TicketOrderMessageConstants.CREATE_ORDER_TOPIC.equals(destinationTopic)
                || !TicketOrderMessageConstants.CREATE_ORDER_TAG.equals(destinationTag)
                || delayLevel != 0) {
            throw new IllegalStateException("订单创建 Outbox 主键或命令字段不一致");
        }
    }

    /**
     * 校验订单结果 Outbox 列与回传消息使用同一稳定关联键。
     *
     * @param message 反序列化后的订单创建结果
     * @param eventId Outbox 事件标识
     * @param deduplicationKey Outbox 业务去重键
     * @param aggregateId Outbox 聚合标识
     * @param messageKey Outbox RocketMQ 业务键
     * @param eventVersion Outbox 协议版本
     * @param destinationTopic 目标 Topic
     * @param destinationTag 目标 Tag
     * @param delayLevel RocketMQ 延迟级别
     */
    private void validateResult(
            TicketOrderCreateResultMessage message,
            String eventId,
            String deduplicationKey,
            String aggregateId,
            String messageKey,
            long eventVersion,
            String destinationTopic,
            String destinationTag,
            int delayLevel) {
        boolean succeeded = TicketOrderMessageConstants.RESULT_SUCCEEDED.equals(message.getStatus());
        boolean failed = TicketOrderMessageConstants.RESULT_FAILED.equals(message.getStatus());
        if (message.getMessageVersion() == null
                || message.getMessageVersion() != eventVersion
                || message.getMessageVersion() != TicketOrderMessageConstants.CREATE_ORDER_VERSION
                || !eventId.equals(message.getMessageId())
                || !deduplicationKey.equals(message.getSourceMessageId())
                || !aggregateId.equals(message.getReservationId())
                || !hasText(message.getCommandId())
                || !messageKey.equals(message.getCommandId())
                || !TicketOrderMessageConstants.CREATE_ORDER_TOPIC.equals(destinationTopic)
                || !TicketOrderMessageConstants.CREATE_ORDER_RESULT_TAG.equals(destinationTag)
                || delayLevel != 0
                || (!succeeded && !failed)
                || (succeeded && !hasText(message.getOrderSn()))) {
            throw new IllegalStateException("订单创建结果 Outbox 主键或结果字段不一致");
        }
    }

    /**
     * 校验延迟关单 Outbox 字段、兼容消息包装器和目标延迟等级使用同一稳定业务事实。
     *
     * @param message 已反序列化的延迟关单消息
     * @param eventId Outbox 稳定事件标识
     * @param deduplicationKey Outbox 业务去重键
     * @param aggregateId 待关闭订单号
     * @param messageKey RocketMQ 业务键
     * @param eventVersion Outbox 协议版本
     * @param destinationTopic 目标 Topic
     * @param destinationTag 目标 Tag
     * @param delayLevel RocketMQ 延迟等级
     */
    private void validateDelayClose(
            TicketOrderDelayCloseMessage message,
            String eventId,
            String deduplicationKey,
            String aggregateId,
            String messageKey,
            long eventVersion,
            String destinationTopic,
            String destinationTag,
            int delayLevel) {
        if (message.getMessage() == null
                || message.getMessageVersion() == null
                || message.getMessageVersion() != eventVersion
                || message.getMessageVersion() != TicketOrderMessageConstants.DELAY_CLOSE_ORDER_VERSION
                || !eventId.equals(message.getMessageId())
                || !eventId.equals(message.getUuid())
                || !deduplicationKey.equals(message.getCommandId())
                || !aggregateId.equals(message.getKeys())
                || !aggregateId.equals(messageKey)
                || !aggregateId.equals(message.getMessage().getOrderSn())
                || !isSameUniqueNameDestination(destinationTopic, destinationTag)
                || delayLevel != TicketOrderMessageConstants.DELAY_CLOSE_ORDER_DELAY_LEVEL) {
            throw new IllegalStateException("延迟关单 Outbox 主键或消息字段不一致");
        }
    }

    /**
     * 校验延迟关单 Topic 与 Tag 均由同一个 unique-name 后缀生成。
     *
     * @param destinationTopic Outbox 固化的目标 Topic
     * @param destinationTag Outbox 固化的目标 Tag
     * @return 前缀合法且后缀完全一致时返回 true
     */
    private boolean isSameUniqueNameDestination(String destinationTopic, String destinationTag) {
        String topicPrefix = TicketOrderMessageConstants.DELAY_CLOSE_ORDER_TOPIC;
        String tagPrefix = TicketOrderMessageConstants.DELAY_CLOSE_ORDER_TAG;
        if (!destinationTopic.startsWith(topicPrefix) || !destinationTag.startsWith(tagPrefix)) {
            return false;
        }
        // 开发、压测等环境可追加 unique-name，二者必须使用同一后缀避免投递到错误消费组。
        return destinationTopic.substring(topicPrefix.length())
                .equals(destinationTag.substring(tagPrefix.length()));
    }

    /**
     * 解析非负延迟级别，普通建单命令只能使用零延迟。
     *
     * @param value Binlog 列值
     * @param name 列名
     * @return 大于等于零的延迟级别
     */
    private int parseNonNegativeInt(String value, String name) {
        try {
            int result = Integer.parseInt(value);
            if (result < 0) {
                throw new IllegalArgumentException(name + " 不能为负数");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("订单创建 Outbox 列不是整数：" + name, exception);
        }
    }

    /**
     * 获取必填列，并拒绝缺失或空白的 Binlog 字段。
     *
     * @param columns INSERT 后镜像列集合
     * @param name 必填列名
     * @return 非空白列值
     */
    private String requireColumn(Map<String, String> columns, String name) {
        String value = columns.get(name);
        if (!hasText(value)) {
            throw new IllegalStateException("订单创建 Outbox 缺少列：" + name);
        }
        return value;
    }

    /**
     * 解析正整数版本号。
     *
     * @param value Binlog 列值
     * @param name 列名
     * @return 正数版本号
     */
    private long parsePositiveLong(String value, String name) {
        try {
            long result = Long.parseLong(value);
            if (result <= 0) {
                throw new IllegalArgumentException(name + " 必须为正数");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("订单创建 Outbox 列不是整数：" + name, exception);
        }
    }

    /**
     * 判断字符串是否含有有效字符。
     *
     * @param value 待检查的字符串
     * @return 存在非空白字符时返回 true
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Relay 已校验、可投递的订单 CDC 事件。
     *
     * @param eventId Outbox 稳定事件标识
     * @param aggregateId Outbox 聚合标识，例如座位预留标识或订单号
     * @param commandId 订单侧幂等命令标识
     * @param destinationTopic 已校验的目标 Topic
     * @param destinationTag 已校验的目标 Tag
     * @param delayLevel 已校验的 RocketMQ 延迟级别
     * @param payload 已校验的共享 MQ 消息快照
     */
    public record RelayCommand(
            String eventId,
            String aggregateId,
            String commandId,
            String destinationTopic,
            String destinationTag,
            int delayLevel,
            Object payload) {
    }
}
