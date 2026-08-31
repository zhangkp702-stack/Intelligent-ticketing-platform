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
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.canalrelay.config.CanalRelayProperties;
import org.opengoofy.index12306.biz.canalrelay.relay.CanalOutboxMessageRouter.RelayCommand;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderDelayCloseEvent;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderDelayCloseMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Canal Outbox INSERT 到共享建单命令的字段一致性边界。
 */
class CanalOutboxMessageRouterTests {

    /**
     * 完整 Outbox INSERT 应被还原为同一条稳定建单命令。
     */
    @Test
    void routesValidOrderCreationInsert() {
        TicketOrderCreateCommandMessage message = command("reservation-a", "purchase-a:create-order", 1L);
        CanalOutboxMessageRouter router = new CanalOutboxMessageRouter(properties());

        List<RelayCommand> commands = router.route(List.of(insertEntry(columns(message, 1L))));

        assertThat(commands).singleElement().satisfies(command -> {
            assertThat(command.eventId()).isEqualTo("reservation-a");
            assertThat(command.aggregateId()).isEqualTo("reservation-a");
            assertThat(command.commandId()).isEqualTo("purchase-a:create-order");
            assertThat(command.payload()).isEqualTo(message);
        });
    }

    /**
     * 订单分库的终态结果 INSERT 应按结果 Tag 还原为同一条共享结果消息。
     */
    @Test
    void routesValidOrderCreationResultInsert() {
        TicketOrderCreateResultMessage message = TicketOrderCreateResultMessage.builder()
                .messageId("reservation-a:result")
                .messageVersion(1L)
                .sourceMessageId("reservation-a")
                .reservationId("reservation-a")
                .commandId("purchase-a:create-order")
                .status(TicketOrderMessageConstants.RESULT_SUCCEEDED)
                .orderSn("order-1")
                .build();
        CanalOutboxMessageRouter router = new CanalOutboxMessageRouter(properties());

        List<RelayCommand> commands = router.route(List.of(entry(
                "12306_order_0", "t_cdc_outbox_event_7", CanalEntry.EventType.INSERT, resultColumns(message))));

        assertThat(commands).singleElement().satisfies(command -> {
            assertThat(command.eventId()).isEqualTo("reservation-a:result");
            assertThat(command.aggregateId()).isEqualTo("reservation-a");
            assertThat(command.commandId()).isEqualTo("purchase-a:create-order");
            assertThat(command.destinationTag()).isEqualTo(TicketOrderMessageConstants.CREATE_ORDER_RESULT_TAG);
            assertThat(command.payload()).isEqualTo(message);
        });
    }

    /**
     * 订单分库的延迟关单 INSERT 应保留既有包装字段并传递十分钟延迟等级。
     */
    @Test
    void routesValidDelayCloseOrderInsert() {
        TicketOrderDelayCloseMessage message = TicketOrderDelayCloseMessage.builder()
                .messageId("purchase-a:create-order:delay-close")
                .messageVersion(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_VERSION)
                .commandId("purchase-a:create-order")
                .keys("order-1")
                .message(TicketOrderDelayCloseEvent.builder().orderSn("order-1").build())
                .uuid("purchase-a:create-order:delay-close")
                .timestamp(0L)
                .build();
        CanalOutboxMessageRouter router = new CanalOutboxMessageRouter(properties());

        List<RelayCommand> commands = router.route(List.of(entry(
                "12306_order_1", "t_cdc_outbox_event_12", CanalEntry.EventType.INSERT,
                delayCloseColumns(message))));

        assertThat(commands).singleElement().satisfies(command -> {
            assertThat(command.eventId()).isEqualTo("purchase-a:create-order:delay-close");
            assertThat(command.aggregateId()).isEqualTo("order-1");
            assertThat(command.commandId()).isEqualTo("purchase-a:create-order");
            assertThat(command.destinationTopic()).isEqualTo(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_TOPIC);
            assertThat(command.destinationTag()).isEqualTo(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_TAG);
            assertThat(command.delayLevel()).isEqualTo(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_DELAY_LEVEL);
            assertThat(command.payload()).isEqualTo(message);
        });
    }

    /**
     * 非 INSERT 或其他表变更只会被 ACK，不得误投递订单创建主题。
     */
    @Test
    void ignoresUpdatesAndOtherTables() {
        TicketOrderCreateCommandMessage message = command("reservation-a", "purchase-a:create-order", 1L);
        CanalOutboxMessageRouter router = new CanalOutboxMessageRouter(properties());

        List<RelayCommand> commands = router.route(List.of(
                entry("12306_ticket", "t_cdc_outbox_event", CanalEntry.EventType.UPDATE, columns(message, 1L)),
                entry("12306_order_0", "t_cdc_outbox_event", CanalEntry.EventType.INSERT, columns(message, 1L))));

        assertThat(commands).isEmpty();
    }

    /**
     * Outbox 列与命令主键不一致时必须使批次失败，不能静默丢弃该事件。
     */
    @Test
    void rejectsEventWhenCommandKeyDoesNotMatchOutbox() {
        TicketOrderCreateCommandMessage message = command("reservation-a", "purchase-a:create-order", 1L);
        Map<String, String> columns = columns(message, 1L);
        columns.put("event_id", "different-event-id");
        CanalOutboxMessageRouter router = new CanalOutboxMessageRouter(properties());

        assertThatThrownBy(() -> router.route(List.of(insertEntry(columns))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("主键或命令字段不一致");
    }

    /**
     * 创建 Relay 使用的固定源库表配置。
     *
     * @return 与生产默认值一致的最小测试配置
     */
    private CanalRelayProperties properties() {
        return new CanalRelayProperties();
    }

    /**
     * 构造完整的共享订单创建命令。
     *
     * @param reservationId 座位预留标识
     * @param commandId 稳定建单命令标识
     * @param version 命令协议版本
     * @return 可写入 Outbox 的不可变命令
     */
    private TicketOrderCreateCommandMessage command(String reservationId, String commandId, long version) {
        TicketOrderCreateItem item = TicketOrderCreateItem.builder()
                .carriageNumber("01")
                .seatType(1)
                .seatNumber("01A")
                .realName("alice")
                .idType(1)
                .idCard("id-card")
                .phone("13800000000")
                .amount(100)
                .ticketType(0)
                .build();
        TicketOrderCreateRequest request = TicketOrderCreateRequest.builder()
                .userId("1001")
                .username("alice")
                .ticketOrderItems(List.of(item))
                .build();
        return TicketOrderCreateCommandMessage.builder()
                .messageId(reservationId)
                .messageVersion(version)
                .reservationId(reservationId)
                .actionId("purchase-a")
                .commandId(commandId)
                .request(request)
                .build();
    }

    /**
     * 构造一条可靠 Outbox 的 INSERT 后镜像列。
     *
     * @param message 共享建单命令
     * @param eventVersion Outbox 事件版本
     * @return 保持列顺序的 Binlog 列集合
     */
    private Map<String, String> columns(TicketOrderCreateCommandMessage message, long eventVersion) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("namespace", TicketOrderMessageConstants.CREATE_ORDER_NAMESPACE);
        columns.put("event_id", message.getMessageId());
        columns.put("deduplication_key", message.getCommandId());
        columns.put("event_type", TicketOrderMessageConstants.CREATE_ORDER_EVENT_TYPE);
        columns.put("aggregate_id", message.getReservationId());
        columns.put("message_key", message.getCommandId());
        columns.put("destination_topic", TicketOrderMessageConstants.CREATE_ORDER_TOPIC);
        columns.put("destination_tag", TicketOrderMessageConstants.CREATE_ORDER_TAG);
        columns.put("delay_level", "0");
        columns.put("payload", JSON.toJSONString(message));
        columns.put("event_version", String.valueOf(eventVersion));
        return columns;
    }

    /**
     * 构造订单分库 CDC Outbox 的订单创建结果 INSERT 后镜像列。
     *
     * @param message 订单终态回传消息
     * @return 与迁移表字段一致的结果事件列集合
     */
    private Map<String, String> resultColumns(TicketOrderCreateResultMessage message) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("namespace", TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE);
        columns.put("event_id", message.getMessageId());
        columns.put("deduplication_key", message.getSourceMessageId());
        columns.put("event_type", TicketOrderMessageConstants.CREATE_ORDER_RESULT_EVENT_TYPE);
        columns.put("aggregate_id", message.getReservationId());
        columns.put("message_key", message.getCommandId());
        columns.put("destination_topic", TicketOrderMessageConstants.CREATE_ORDER_TOPIC);
        columns.put("destination_tag", TicketOrderMessageConstants.CREATE_ORDER_RESULT_TAG);
        columns.put("delay_level", "0");
        columns.put("payload", JSON.toJSONString(message));
        columns.put("event_version", String.valueOf(message.getMessageVersion()));
        return columns;
    }

    /**
     * 构造订单分库 CDC Outbox 的延迟关单 INSERT 后镜像列。
     *
     * @param message 保留历史包装字段的延迟关单消息
     * @return 与迁移表字段一致的延迟关单事件列集合
     */
    private Map<String, String> delayCloseColumns(TicketOrderDelayCloseMessage message) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("namespace", TicketOrderMessageConstants.DELAY_CLOSE_ORDER_NAMESPACE);
        columns.put("event_id", message.getMessageId());
        columns.put("deduplication_key", message.getCommandId());
        columns.put("event_type", TicketOrderMessageConstants.DELAY_CLOSE_ORDER_EVENT_TYPE);
        columns.put("aggregate_id", message.getKeys());
        columns.put("message_key", message.getKeys());
        columns.put("destination_topic", TicketOrderMessageConstants.DELAY_CLOSE_ORDER_TOPIC);
        columns.put("destination_tag", TicketOrderMessageConstants.DELAY_CLOSE_ORDER_TAG);
        columns.put("delay_level", String.valueOf(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_DELAY_LEVEL));
        columns.put("payload", JSON.toJSONString(message));
        columns.put("event_version", String.valueOf(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_VERSION));
        return columns;
    }

    /**
     * 构造目标表的一条 INSERT Binlog 条目。
     *
     * @param columns Outbox INSERT 后镜像列
     * @return 可供路由器解析的 Canal 条目
     */
    private CanalEntry.Entry insertEntry(Map<String, String> columns) {
        return entry("12306_ticket", "t_cdc_outbox_event", CanalEntry.EventType.INSERT, columns);
    }

    /**
     * 构造任意库表与事件类型的 Canal 行数据条目。
     *
     * @param schema 数据库名
     * @param table 表名
     * @param eventType Binlog 事件类型
     * @param columns INSERT 或 UPDATE 后镜像列
     * @return Canal ROWDATA 条目
     */
    private CanalEntry.Entry entry(
            String schema,
            String table,
            CanalEntry.EventType eventType,
            Map<String, String> columns) {
        CanalEntry.RowData.Builder rowData = CanalEntry.RowData.newBuilder();
        // 测试数据只构造 afterColumns，确保生产代码没有意外依赖 beforeColumns。
        columns.forEach((name, value) -> rowData.addAfterColumns(
                CanalEntry.Column.newBuilder().setName(name).setValue(value).build()));
        CanalEntry.RowChange rowChange = CanalEntry.RowChange.newBuilder()
                .setEventType(eventType)
                .addRowDatas(rowData.build())
                .build();
        return CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName(schema).setTableName(table).build())
                .setStoreValue(rowChange.toByteString())
                .build();
    }
}
