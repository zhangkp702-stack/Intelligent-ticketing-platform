package org.opengoofy.index12306.ai.mcpserver.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.opengoofy.index12306.ai.mcpserver.config.TicketMcpProperties;
import org.opengoofy.index12306.ai.mcpserver.security.McpCallerIdentity;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.OrderPage;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.OrderView;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.PassengerView;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.SeatAvailability;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.StationMatch;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.TicketSearchResult;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.TrainStop;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.TrainTicket;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过现有票务、用户和订单 HTTP 接口读取业务数据，并将响应收敛为 MCP 白名单字段。
 */
@Component
public class TicketBusinessClient {

    private static final String SUCCESS_CODE = "0";
    private static final int TRAIN_STOP_LIMIT = 100;
    private static final int SEAT_TYPE_LIMIT = 20;

    private final RestClient ticketClient;
    private final RestClient userClient;
    private final RestClient orderClient;
    private final TicketMcpProperties properties;

    /**
     * 创建三个下游服务客户端，并统一应用短连接和读取超时。
     *
     * @param builder Spring 提供的 REST 客户端构建器
     * @param properties MCP 下游服务配置
     */
    public TicketBusinessClient(RestClient.Builder builder, TicketMcpProperties properties) {
        // 使用同一请求工厂保证所有业务查询都受到明确超时约束。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.ticketClient = builder.clone()
                .baseUrl(properties.ticketServiceUrl().toString())
                .requestFactory(requestFactory)
                .build();
        this.userClient = builder.clone()
                .baseUrl(properties.userServiceUrl().toString())
                .requestFactory(requestFactory)
                .build();
        this.orderClient = builder.clone()
                .baseUrl(properties.orderServiceUrl().toString())
                .requestFactory(requestFactory)
                .build();
        this.properties = properties;
    }

    /**
     * 按名称或拼音前缀解析站点，并限制返回数量。
     *
     * @param keyword 站点名称或拼音前缀
     * @param identity 已验证的调用者身份
     * @return 站点候选列表
     */
    public List<StationMatch> resolveStation(String keyword, McpCallerIdentity identity) {
        // 调用现有站点解析接口，保留名称、编码和拼音三个稳定字段。
        JsonNode root = ticketClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ticket-service/region-station/query")
                        .queryParam("name", keyword)
                        .queryParam("queryType", 0)
                        .build())
                .headers(headers -> addIdentity(headers, identity))
                .retrieve()
                .body(JsonNode.class);
        JsonNode data = requireData(root);
        List<StationMatch> result = new ArrayList<>();
        for (JsonNode item : iterable(data)) {
            if (result.size() >= properties.stationResultLimit()) {
                break;
            }
            result.add(new StationMatch(text(item, "name"), text(item, "code"), text(item, "spell")));
        }
        return List.copyOf(result);
    }

    /**
     * 查询指定日期和区间的可售车次，将席别信息限制在稳定白名单字段内。
     *
     * @param fromStationCode 出发站编码
     * @param toStationCode 到达站编码
     * @param departure 出发站名称
     * @param arrival 到达站名称
     * @param departureDate 出发日期
     * @param identity 已验证的调用者身份
     * @return 限量车次查询结果
     */
    public TicketSearchResult queryTickets(
            String fromStationCode,
            String toStationCode,
            String departure,
            String arrival,
            LocalDate departureDate,
            McpCallerIdentity identity) {
        // 调用票务服务的现有查询流程，以继续复用库存、价格和风控规则。
        JsonNode root = ticketClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ticket-service/ticket/query")
                        .queryParam("fromStation", fromStationCode)
                        .queryParam("toStation", toStationCode)
                        .queryParam("departure", departure)
                        .queryParam("arrival", arrival)
                        .queryParam("departureDate", departureDate)
                        .queryParam("current", 1)
                        .queryParam("size", properties.ticketResultLimit())
                        .build())
                .headers(headers -> addIdentity(headers, identity))
                .retrieve()
                .body(JsonNode.class);
        JsonNode data = requireData(root);
        JsonNode trainList = data.path("trainList");
        List<TrainTicket> trains = new ArrayList<>();
        for (JsonNode train : iterable(trainList)) {
            if (trains.size() >= properties.ticketResultLimit()) {
                break;
            }
            trains.add(toTrainTicket(train));
        }
        boolean truncated = trainList.isArray() && trainList.size() > trains.size();
        return new TicketSearchResult(List.copyOf(trains), truncated);
    }

    /**
     * 查询列车沿途经停站，并限制异常响应的最大记录数。
     *
     * @param trainId 列车内部标识
     * @param identity 已验证的调用者身份
     * @return 经停站列表
     */
    public List<TrainStop> queryTrainStops(String trainId, McpCallerIdentity identity) {
        // 复用列车站点查询接口，转换时间和停留时长为适合模型读取的字段。
        JsonNode root = ticketClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ticket-service/train-station/query")
                        .queryParam("trainId", trainId)
                        .build())
                .headers(headers -> addIdentity(headers, identity))
                .retrieve()
                .body(JsonNode.class);
        JsonNode data = requireData(root);
        List<TrainStop> result = new ArrayList<>();
        for (JsonNode item : iterable(data)) {
            if (result.size() >= TRAIN_STOP_LIMIT) {
                break;
            }
            result.add(new TrainStop(
                    text(item, "sequence"),
                    text(item, "departure"),
                    text(item, "arrivalTime"),
                    text(item, "departureTime"),
                    integer(item, "stopoverTime")));
        }
        return List.copyOf(result);
    }

    /**
     * 查询当前用户的常用乘车人，仅返回脱敏证件和手机号。
     *
     * @param identity 已验证的调用者身份
     * @return 脱敏乘车人列表
     */
    public List<PassengerView> listPassengers(McpCallerIdentity identity) {
        // 身份请求头只由验证后的 MCP 元数据生成，模型不能指定其他用户名。
        JsonNode root = userClient.get()
                .uri("/api/user-service/passenger/query")
                .headers(headers -> addIdentity(headers, identity))
                .retrieve()
                .body(JsonNode.class);
        JsonNode data = requireData(root);
        List<PassengerView> result = new ArrayList<>();
        for (JsonNode item : iterable(data)) {
            if (result.size() >= properties.passengerResultLimit()) {
                break;
            }
            result.add(new PassengerView(
                    text(item, "id"),
                    text(item, "realName"),
                    integer(item, "idType"),
                    text(item, "idCard"),
                    integer(item, "discountType"),
                    text(item, "phone"),
                    integer(item, "verifyStatus")));
        }
        return List.copyOf(result);
    }

    /**
     * 分页查询当前用户的订单，页大小由服务端上限再次约束。
     *
     * @param current 当前页
     * @param size 请求页大小
     * @param identity 已验证的调用者身份
     * @return 本人订单分页结果
     */
    public OrderPage listOrders(long current, int size, McpCallerIdentity identity) {
        // 强制限制单页数量，防止模型一次调用加载过多订单上下文。
        int boundedSize = Math.min(size, properties.orderPageSizeLimit());
        JsonNode root = orderClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/order-service/order/ticket/self/page")
                        .queryParam("current", current)
                        .queryParam("size", boundedSize)
                        .build())
                .headers(headers -> addIdentity(headers, identity))
                .retrieve()
                .body(JsonNode.class);
        JsonNode data = requireData(root);
        List<OrderView> orders = new ArrayList<>();
        for (JsonNode item : iterable(data.path("records"))) {
            if (orders.size() >= boundedSize) {
                break;
            }
            orders.add(new OrderView(
                    text(item, "departure"),
                    text(item, "arrival"),
                    text(item, "ridingDate"),
                    text(item, "trainNumber"),
                    text(item, "departureTime"),
                    text(item, "arrivalTime"),
                    integer(item, "seatType"),
                    text(item, "carriageNumber"),
                    text(item, "seatNumber"),
                    text(item, "realName"),
                    integer(item, "ticketType"),
                    integer(item, "amount")));
        }
        return new OrderPage(
                data.path("current").asLong(current),
                data.path("size").asLong(boundedSize),
                data.path("total").asLong(orders.size()),
                List.copyOf(orders));
    }

    /**
     * 将车次 JSON 转换为不包含内部库存实现细节的工具响应。
     *
     * @param train 下游车次节点
     * @return 白名单车次响应
     */
    private TrainTicket toTrainTicket(JsonNode train) {
        // 席别列表独立限量，避免异常下游数据放大单次工具响应。
        List<SeatAvailability> seats = new ArrayList<>();
        for (JsonNode seat : iterable(train.path("seatClassList"))) {
            if (seats.size() >= SEAT_TYPE_LIMIT) {
                break;
            }
            seats.add(new SeatAvailability(
                    integer(seat, "type"),
                    integer(seat, "quantity"),
                    text(seat, "price"),
                    bool(seat, "candidate")));
        }
        return new TrainTicket(
                text(train, "trainId"),
                text(train, "trainNumber"),
                text(train, "departureTime"),
                text(train, "arrivalTime"),
                text(train, "duration"),
                integer(train, "daysArrived"),
                text(train, "departure"),
                text(train, "arrival"),
                text(train, "saleTime"),
                integer(train, "saleStatus"),
                List.copyOf(seats));
    }

    /**
     * 验证旧业务服务的统一响应信封并提取 data 字段。
     *
     * @param root 完整响应节点
     * @return 业务数据节点
     */
    private JsonNode requireData(JsonNode root) {
        // 仅 code=0 视为成功，错误消息做长度限制后转为工具失败。
        if (root == null || !SUCCESS_CODE.equals(root.path("code").asText())) {
            String message = root == null ? "empty downstream response" : root.path("message").asText("downstream error");
            throw new IllegalStateException("Ticket service query failed: " + abbreviate(message, 200));
        }
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new IllegalStateException("Ticket service query returned no data");
        }
        return data;
    }

    /**
     * 将验证后的用户身份写入旧业务服务识别的请求头。
     *
     * @param headers HTTP 请求头
     * @param identity 已验证身份
     */
    private void addIdentity(HttpHeaders headers, McpCallerIdentity identity) {
        // 用户名按网关既有规则编码，避免非 ASCII 姓名破坏 HTTP 头传输。
        headers.set("userId", identity.userId());
        if (identity.username() != null && !identity.username().isBlank()) {
            headers.set("username", URLEncoder.encode(identity.username(), StandardCharsets.UTF_8));
        }
        headers.set("X-Agent-Request-Id", identity.requestId());
    }

    /**
     * 将数组节点转换为可安全迭代对象，非数组返回空集合。
     *
     * @param node JSON 节点
     * @return 可迭代节点集合
     */
    private Iterable<JsonNode> iterable(JsonNode node) {
        // 下游结构异常时返回空集合，让调用结果保持确定性。
        return node != null && node.isArray() ? node : List.of();
    }

    /**
     * 读取允许为空的文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 文本值或 null
     */
    private String text(JsonNode node, String field) {
        // 缺失和 JSON null 均映射为 Java null。
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 读取允许为空的整数字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 整数值或 null
     */
    private Integer integer(JsonNode node, String field) {
        // 缺失和 JSON null 不应被误报为业务值 0。
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    /**
     * 读取允许为空的布尔字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 布尔值或 null
     */
    private Boolean bool(JsonNode node, String field) {
        // 缺失和 JSON null 保留为空，避免改变候补语义。
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    /**
     * 限制下游错误文本长度，避免把大响应写入模型工具错误。
     *
     * @param value 原始文本
     * @param maxLength 最大长度
     * @return 限长文本
     */
    private String abbreviate(String value, int maxLength) {
        // 短文本原样返回，长文本只保留诊断所需前缀。
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
