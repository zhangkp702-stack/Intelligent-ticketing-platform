package org.opengoofy.index12306.ai.mcpserver.client;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.mcpserver.config.TicketMcpProperties;
import org.opengoofy.index12306.ai.mcpserver.security.McpCallerIdentity;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.ConfirmedCancellationResult;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.ConfirmedPurchasePassenger;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.ConfirmedPurchaseResult;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.ConfirmedRefundResult;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证 MCP 购票客户端与票务服务之间的 V2 请求契约。
 */
class TicketBusinessClientTests {

    /**
     * 验证确认购票把 actionId 作为 operationId 发送到 V2 接口。
     */
    @Test
    void confirmedPurchaseUsesV2EndpointAndPersistentOperationId() {
        TicketBusinessClient client = new TicketBusinessClient(RestClient.builder(), properties());
        RestClient.Builder userBuilder = RestClient.builder().baseUrl("http://user.test");
        MockRestServiceServer userServer = MockRestServiceServer.bindTo(userBuilder).build();
        RestClient.Builder ticketBuilder = RestClient.builder().baseUrl("http://ticket.test");
        MockRestServiceServer ticketServer = MockRestServiceServer.bindTo(ticketBuilder).build();
        ReflectionTestUtils.setField(client, "userClient", userBuilder.build());
        ReflectionTestUtils.setField(client, "ticketClient", ticketBuilder.build());

        // 乘车人白名单查询先确认当前账号拥有请求中的乘车人。
        userServer.expect(requestTo("http://user.test/api/user-service/passenger/query"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                                {"code":"0","data":[{"id":"passenger-1","realName":"张三","idType":0,
                                "idCard":"1***********1","discountType":0,"phone":"138****0000","verifyStatus":1}]}
                                """,
                        MediaType.APPLICATION_JSON));
        ticketServer.expect(requestTo("http://ticket.test/api/ticket-service/ticket/purchase/v2"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        """
                                {"operationId":"action-1","trainId":"train-1","departure":"北京南",
                                "arrival":"上海虹桥","departureDate":"2026-07-30",
                                "passengers":[{"passengerId":"passenger-1","seatType":3}],"chooseSeats":["A"]}
                                """,
                        JsonCompareMode.LENIENT))
                .andRespond(withSuccess(
                        """
                                {"code":"0","data":{"orderSn":"order-1","ticketOrderDetails":[]}}
                                """,
                        MediaType.APPLICATION_JSON));
        McpCallerIdentity identity = new McpCallerIdentity(
                "request-1", "user-1", "alice", "conversation-1", "turn-1", "action-1", "payload-hash");

        // 请求成功后 MCP 只返回脱敏订单号和车票展示字段。
        ConfirmedPurchaseResult result = client.purchase(
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-30",
                List.of(new ConfirmedPurchasePassenger("passenger-1", 3)),
                List.of("A"),
                identity);

        assertThat(result.orderSn()).isEqualTo("order-1");
        assertThat(result.tickets()).isEmpty();
        userServer.verify();
        ticketServer.verify();
    }

    /**
     * 验证确认取消把 actionId 作为 operationId 发送到票务服务。
     */
    @Test
    void confirmedCancellationSendsPersistentOperationId() {
        TicketBusinessClient client = new TicketBusinessClient(RestClient.builder(), properties());
        RestClient.Builder ticketBuilder = RestClient.builder().baseUrl("http://ticket.test");
        MockRestServiceServer ticketServer = MockRestServiceServer.bindTo(ticketBuilder).build();
        ReflectionTestUtils.setField(client, "ticketClient", ticketBuilder.build());
        ticketServer.expect(requestTo("http://ticket.test/api/ticket-service/ticket/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        """
                                {"operationId":"action-1","orderSn":"order-1"}
                                """,
                        JsonCompareMode.LENIENT))
                .andRespond(withSuccess(
                        """
                                {"code":"0"}
                                """,
                        MediaType.APPLICATION_JSON));
        McpCallerIdentity identity = new McpCallerIdentity(
                "request-1", "user-1", "alice", "conversation-1", "turn-1", "action-1", "payload-hash");

        // 取消成功结果只返回订单号和稳定布尔状态。
        ConfirmedCancellationResult result = client.cancelOrder("order-1", identity);

        assertThat(result.orderSn()).isEqualTo("order-1");
        assertThat(result.cancelled()).isTrue();
        ticketServer.verify();
    }

    /**
     * 验证确认退票使用同一个 actionId 贯通票务操作和支付退款幂等键。
     */
    @Test
    void confirmedRefundUsesActionIdAcrossBothIdempotencyFields() {
        TicketBusinessClient client = new TicketBusinessClient(RestClient.builder(), properties());
        RestClient.Builder ticketBuilder = RestClient.builder().baseUrl("http://ticket.test");
        MockRestServiceServer ticketServer = MockRestServiceServer.bindTo(ticketBuilder).build();
        ReflectionTestUtils.setField(client, "ticketClient", ticketBuilder.build());
        ticketServer.expect(requestTo("http://ticket.test/api/ticket-service/ticket/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        """
                                {"operationId":"action-1","requestId":"action-1","orderSn":"order-1",
                                "type":0,"subOrderRecordIdReqList":["item-1"]}
                                """,
                        JsonCompareMode.LENIENT))
                .andRespond(withSuccess(
                        """
                                {"code":"0","data":{"requestId":"action-1","orderSn":"order-1",
                                "type":0,"refundAmount":100,"status":1}}
                                """,
                        MediaType.APPLICATION_JSON));
        McpCallerIdentity identity = new McpCallerIdentity(
                "request-1", "user-1", "alice", "conversation-1", "turn-1", "action-1", "payload-hash");

        // 退款结果只保留稳定业务字段，不暴露支付渠道内部交易凭证。
        ConfirmedRefundResult result = client.refundTicket(
                "action-1", "order-1", 0, List.of("item-1"), identity);

        assertThat(result.requestId()).isEqualTo("action-1");
        assertThat(result.refundAmount()).isEqualTo(100);
        ticketServer.verify();
    }

    /**
     * 构造不会访问真实下游服务的 MCP 客户端配置。
     *
     * @return 测试配置
     */
    private TicketMcpProperties properties() {
        return new TicketMcpProperties(
                "test-secret",
                Duration.ofMinutes(2),
                URI.create("http://127.0.0.1:9002"),
                URI.create("http://127.0.0.1:9001"),
                URI.create("http://127.0.0.1:9003"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                10,
                20,
                20);
    }
}
