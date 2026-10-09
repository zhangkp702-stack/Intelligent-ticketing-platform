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
import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryEvent;
import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryStatus;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderDelayCloseMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;

/**
 * 将目标 Outbox 的 INSERT Binlog 记录还原为可投递的订单事件。
 *
 * <p>路由器不修改来源数据库；生产逐条路由会把字段校验失败转换为 DEAD 任务，避免坏事件阻塞同批合法消息。</p>
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
     * 按单条行事件还原 Outbox 消息，并将解析或校验失败隔离为 DEAD 任务。
     *
     * @param entries Canal 返回的原始 Binlog 条目
     * @return 每条可处理行的成功命令或待持久化失败结果
     */
    public List<RouteResult> routeIndividually(List<CanalEntry.Entry> entries) {
        List<RouteResult> results = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return results;
        }
        // 单条解析失败只记录当前条目，避免同批其他合法事件被迫回滚重放。
        for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
            CanalEntry.Entry entry = entries.get(entryIndex);
            if (!isTargetRowData(entry)) {
                continue;
            }
            CanalEntry.RowChange rowChange;
            try {
                rowChange = parseRowChange(entry);
            } catch (RuntimeException exception) {
                results.add(RouteResult.dead(buildDeadEvent(entry, entryIndex, 0, Map.of(), exception), exception));
                continue;
            }
            if (rowChange.getEventType() != CanalEntry.EventType.INSERT) {
                continue;
            }
            List<CanalEntry.RowData> rowDatas = rowChange.getRowDatasList();
            for (int rowIndex = 0; rowIndex < rowDatas.size(); rowIndex++) {
                Map<String, String> columns = toColumns(rowDatas.get(rowIndex));
                try {
                    results.add(RouteResult.success(routeInsertRow(columns)));
                } catch (RuntimeException exception) {
                    // 只把当前坏行放入 DEAD 队列，其余行仍可继续投递。
                    results.add(RouteResult.dead(buildDeadEvent(entry, entryIndex, rowIndex, columns, exception), exception));
                }
            }
        }
        return List.copyOf(results);
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
        return routeInsertRow(toColumns(rowData));
    }

    /**
     * 从 INSERT 后镜像列还原并校验一条订单事件。
     *
     * @param columns INSERT 后镜像列集合
     * @return 与 Outbox 主键绑定的统一 Relay 命令
     */
    private RelayCommand routeInsertRow(Map<String, String> columns) {
        // 订单命令必须使用 INSERT 后镜像，不能从 beforeColumns 读取旧版本或租约状态。
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
                    namespace, destinationTopic, destinationTag, delayLevel, payload);
        }
        if (TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE.equals(namespace)
                && TicketOrderMessageConstants.CREATE_ORDER_RESULT_EVENT_TYPE.equals(eventType)) {
            return routeOrderCreateResult(eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                    namespace, destinationTopic, destinationTag, delayLevel, payload);
        }
        if (TicketOrderMessageConstants.DELAY_CLOSE_ORDER_NAMESPACE.equals(namespace)
                && TicketOrderMessageConstants.DELAY_CLOSE_ORDER_EVENT_TYPE.equals(eventType)) {
            return routeDelayCloseOrder(eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                    namespace, destinationTopic, destinationTag, delayLevel, payload);
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
     * @param namespace 事件业务域
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
            String namespace,
            String destinationTopic,
            String destinationTag,
            int delayLevel,
            String payload) {
        // 命令载荷只能由锁座事务固定，Relay 不补齐任何票价、乘车人或座位字段。
        TicketOrderCreateCommandMessage message = parseMessage(
                payload, TicketOrderCreateCommandMessage.class, "订单创建 Outbox");
        validateCommand(message, eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                destinationTopic, destinationTag, delayLevel);
        return new RelayCommand(namespace, eventId, aggregateId, message.getReservationId(),
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
     * @param namespace 事件业务域
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
            String namespace,
            String destinationTopic,
            String destinationTag,
            int delayLevel,
            String payload) {
        // 结果载荷已在订单终态事务内固定，Relay 只校验关联键后转发给票务服务。
        TicketOrderCreateResultMessage message = parseMessage(
                payload, TicketOrderCreateResultMessage.class, "订单创建结果 Outbox");
        validateResult(message, eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                destinationTopic, destinationTag, delayLevel);
        return new RelayCommand(namespace, eventId, aggregateId, message.getReservationId(),
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
     * @param namespace 事件业务域
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
            String namespace,
            String destinationTopic,
            String destinationTag,
            int delayLevel,
            String payload) {
        // 延迟消息不能重新计算超时时间，只转发订单事务内固定的十分钟等级和消息快照。
        TicketOrderDelayCloseMessage message = parseMessage(
                payload, TicketOrderDelayCloseMessage.class, "延迟关单 Outbox");
        validateDelayClose(message, eventId, deduplicationKey, aggregateId, messageKey, eventVersion,
                destinationTopic, destinationTag, delayLevel);
        return new RelayCommand(namespace, eventId, aggregateId, deduplicationKey,
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
                || !eventId.equals(message.getReservationId())
                || !aggregateId.equals(message.getReservationId())
                || !deduplicationKey.equals(message.getReservationId())
                || !messageKey.equals(message.getReservationId())
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
                || !deduplicationKey.equals(message.getReservationId())
                || !aggregateId.equals(message.getReservationId())
                || !messageKey.equals(message.getReservationId())
                || !TicketOrderMessageConstants.CREATE_ORDER_TOPIC.equals(destinationTopic)
                || !TicketOrderMessageConstants.CREATE_ORDER_RESULT_TAG.equals(destinationTag)
                || delayLevel != 0
                || (!succeeded && !failed)) {
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
     * 将 Canal 行的 afterColumns 转为便于校验和失败快照构造的列映射。
     *
     * @param rowData Canal 行数据
     * @return INSERT 后镜像列映射
     */
    private Map<String, String> toColumns(CanalEntry.RowData rowData) {
        Map<String, String> columns = new HashMap<>();
        if (rowData == null) {
            return columns;
        }
        // 只读取 INSERT 后镜像，避免将 beforeColumns 中的旧值带入重试任务。
        for (CanalEntry.Column column : rowData.getAfterColumnsList()) {
            columns.put(column.getName(), column.getValue());
        }
        return columns;
    }

    /**
     * 根据稳定 Binlog 位置信息构造坏事件的确定性标识。
     *
     * @param entry Canal 原始条目
     * @param entryIndex 条目在当前批次中的位置
     * @param rowIndex 行在条目中的位置
     * @return 限长的稳定事件标识
     */
    private String fallbackEventId(CanalEntry.Entry entry, int entryIndex, int rowIndex) {
        String logfileName = "";
        long logfileOffset = 0L;
        if (entry != null && entry.hasHeader()) {
            logfileName = entry.getHeader().getLogfileName();
            logfileOffset = entry.getHeader().getLogfileOffset();
        }
        String material = logfileName + ":" + logfileOffset + ":" + entryIndex + ":" + rowIndex;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "invalid:" + HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 将解析或校验异常转换为可持久化的 DEAD 任务快照。
     *
     * @param entry 产生异常的 Canal 条目
     * @param entryIndex 条目在当前批次中的位置
     * @param rowIndex 行在条目中的位置
     * @param columns 已提取的 INSERT 列
     * @param exception 当前条目异常
     * @return 带稳定兜底元数据的失败任务
     */
    private CanalRelayRetryEvent buildDeadEvent(
            CanalEntry.Entry entry,
            int entryIndex,
            int rowIndex,
            Map<String, String> columns,
            RuntimeException exception) {
        String fallbackEventId = fallbackEventId(entry, entryIndex, rowIndex);
        String namespace = bounded(columns.get("namespace"), 64, "canal-relay-invalid");
        String eventId = bounded(columns.get("event_id"), 128, fallbackEventId);
        String aggregateId = bounded(columns.get("aggregate_id"), 128, "unknown");
        String commandId = bounded(columns.get("deduplication_key"), 160, null);
        String destinationTopic = bounded(
                columns.get("destination_topic"), 128, TicketOrderMessageConstants.CREATE_ORDER_TOPIC);
        String destinationTag = bounded(
                columns.get("destination_tag"), 64, TicketOrderMessageConstants.CREATE_ORDER_TAG);
        String payload = columns.get("payload");
        if (!hasText(payload)) {
            byte[] rawValue = entry == null ? new byte[0] : entry.getStoreValue().toByteArray();
            payload = rawValue.length == 0 ? "{}" : Base64.getEncoder().encodeToString(rawValue);
        }
        // retryKey 必须由同一条 Binlog 行稳定生成，重复拉取时 INSERT IGNORE 才能幂等。
        String retryKey = bounded(namespace + ":" + eventId, 191, "canal-relay-invalid:" + fallbackEventId);
        return CanalRelayRetryEvent.builder()
                .retryKey(retryKey)
                .namespace(namespace)
                .eventId(eventId)
                .commandId(commandId)
                .aggregateId(aggregateId)
                .destinationTopic(destinationTopic)
                .destinationTag(destinationTag)
                .delayLevel(0)
                .payload(payload)
                .status(CanalRelayRetryStatus.DEAD)
                .attemptCount(0)
                .maxAttempts(1)
                .nextRetryTime(new Date())
                .lastError(errorSummary(exception))
                .build();
    }

    /**
     * 读取长度合法的列值；异常事件不得因为坏字段本身无法落库。
     *
     * @param value 原始列值
     * @param maxLength 允许的最大长度
     * @param fallback 缺失或越界时的兜底值
     * @return 可持久化的字段值
     */
    private String bounded(String value, int maxLength, String fallback) {
        return hasText(value) && value.length() <= maxLength ? value : fallback;
    }

    /**
     * 生成不携带消息载荷的异常摘要。
     *
     * @param exception 当前异常
     * @return 异常类型和受限消息文本
     */
    private String errorSummary(RuntimeException exception) {
        if (exception == null) {
            return "unknown";
        }
        String message = exception.getMessage();
        if (!hasText(message)) {
            return exception.getClass().getSimpleName();
        }
        return (exception.getClass().getSimpleName() + ": " + message)
                .replace('\n', ' ')
                .replace('\r', ' ');
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
     * 表示单条 CDC 行的路由结果；失败时仅携带待持久化任务，不向下游投递。
     *
     * @param command 已校验的可投递命令
     * @param retryEvent 解析或校验失败的 DEAD 任务
     * @param failure 触发隔离的异常
     */
    public record RouteResult(RelayCommand command, CanalRelayRetryEvent retryEvent, RuntimeException failure) {

        /**
         * 创建成功路由结果。
         *
         * @param command 已校验的命令
         * @return 成功结果
         */
        public static RouteResult success(RelayCommand command) {
            return new RouteResult(command, null, null);
        }

        /**
         * 创建待持久化的坏事件结果。
         *
         * @param retryEvent DEAD 任务
         * @param failure 原始异常
         * @return 隔离结果
         */
        public static RouteResult dead(CanalRelayRetryEvent retryEvent, RuntimeException failure) {
            return new RouteResult(null, retryEvent, failure);
        }
    }

    /**
     * Relay 已校验、可投递的订单 CDC 事件。
     *
     * @param namespace 事件业务域，用于构造稳定重试键
     * @param eventId Outbox 稳定事件标识
     * @param aggregateId Outbox 聚合标识，例如座位预留标识或订单号
     * @param commandId 订单侧幂等命令标识
     * @param destinationTopic 已校验的目标 Topic
     * @param destinationTag 已校验的目标 Tag
     * @param delayLevel 已校验的 RocketMQ 延迟级别
     * @param payload 已校验的共享 MQ 消息快照
     */
    public record RelayCommand(
            String namespace,
            String eventId,
            String aggregateId,
            String commandId,
            String destinationTopic,
            String destinationTag,
            int delayLevel,
            Object payload) {

        /**
         * 兼容测试和旧调用方构造没有显式业务域的 Relay 命令。
         *
         * @param eventId Outbox 稳定事件标识
         * @param aggregateId Outbox 聚合标识
         * @param commandId 订单侧幂等命令标识
         * @param destinationTopic 目标 Topic
         * @param destinationTag 目标 Tag
         * @param delayLevel RocketMQ 延迟级别
         * @param payload 共享消息载荷
         */
        public RelayCommand(
                String eventId,
                String aggregateId,
                String commandId,
                String destinationTopic,
                String destinationTag,
                int delayLevel,
                Object payload) {
            this("canal-relay", eventId, aggregateId, commandId,
                    destinationTopic, destinationTag, delayLevel, payload);
        }
    }
}
