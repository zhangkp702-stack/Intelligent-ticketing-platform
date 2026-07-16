package org.opengoofy.index12306.ai.mcpserver.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.mcpserver.client.TicketBusinessClient;
import org.opengoofy.index12306.ai.mcpserver.security.McpCallerIdentity;
import org.opengoofy.index12306.ai.mcpserver.security.McpRequestAuthenticator;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.OrderPage;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.PassengerView;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.StationMatch;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.TicketSearchResult;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.TrainStop;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.ConfirmedPurchasePassenger;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.ConfirmedPurchaseResult;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 面向购票智能体注册的票务 MCP 工具，所有方法先鉴权再访问业务服务。
 */
@Component
public class TicketQueryTools {

    private static final Pattern STATION_CODE_PATTERN = Pattern.compile("[A-Za-z0-9]{2,16}");
    private static final Pattern TRAIN_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_STATION_NAME_LENGTH = 50;

    private final McpRequestAuthenticator authenticator;
    private final TicketBusinessClient businessClient;
    private final ObjectMapper objectMapper;

    /**
     * 创建包含只读查询和受保护购票执行能力的票务工具集合。
     *
     * @param authenticator MCP 身份鉴权器
     * @param businessClient 票务业务客户端
     * @param objectMapper 参数指纹序列化器
     */
    public TicketQueryTools(
            McpRequestAuthenticator authenticator,
            TicketBusinessClient businessClient,
            ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.businessClient = businessClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据站名或拼音前缀解析可用于余票查询的站点编码。
     *
     * @param keyword 站名或拼音前缀
     * @param meta Agent 签名的 MCP 元数据
     * @return 站点候选列表
     */
    @McpTool(
            name = "resolve_station",
            description = "根据中文站名或拼音前缀解析站点名称和编码；查询余票前应先用它确认出发站与到达站编码。",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "解析车站",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public List<StationMatch> resolveStation(
            @McpToolParam(description = "中文站名或拼音前缀，例如北京、beijing") String keyword,
            McpMeta meta) {
        // 先校验模型可控输入，再验证模型不可见的签名身份。
        requireText(keyword, "keyword", MAX_STATION_NAME_LENGTH);
        McpCallerIdentity identity = authenticator.authenticate(meta);

        // 站点解析结果由票务服务提供，工具层只负责边界校验和安全转发。
        return businessClient.resolveStation(keyword.trim(), identity);
    }

    /**
     * 查询指定日期、出发站和到达站之间的车次、余票与价格。
     *
     * @param fromStationCode 出发站编码
     * @param toStationCode 到达站编码
     * @param departure 出发站名称
     * @param arrival 到达站名称
     * @param departureDate 出发日期，格式 yyyy-MM-dd
     * @param meta Agent 签名的 MCP 元数据
     * @return 限量车次与席别结果
     */
    @McpTool(
            name = "query_tickets",
            description = "查询指定日期和区间的车次、余票与价格，只读取数据，不会锁票或创建订单。",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "查询余票",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public TicketSearchResult queryTickets(
            @McpToolParam(description = "出发站编码，应来自 resolve_station") String fromStationCode,
            @McpToolParam(description = "到达站编码，应来自 resolve_station") String toStationCode,
            @McpToolParam(description = "出发站中文名称") String departure,
            @McpToolParam(description = "到达站中文名称") String arrival,
            @McpToolParam(description = "出发日期，严格使用 yyyy-MM-dd 格式") String departureDate,
            McpMeta meta) {
        // 校验站点编码、展示名称和日期，避免无效参数进入高成本余票查询。
        requirePattern(fromStationCode, "fromStationCode", STATION_CODE_PATTERN);
        requirePattern(toStationCode, "toStationCode", STATION_CODE_PATTERN);
        requireText(departure, "departure", MAX_STATION_NAME_LENGTH);
        requireText(arrival, "arrival", MAX_STATION_NAME_LENGTH);
        LocalDate parsedDate = parseDate(departureDate);
        McpCallerIdentity identity = authenticator.authenticate(meta);

        // 通过已验证身份调用既有余票接口并返回服务端限量结果。
        return businessClient.queryTickets(
                fromStationCode.trim(),
                toStationCode.trim(),
                departure.trim(),
                arrival.trim(),
                parsedDate,
                identity);
    }

    /**
     * 查询指定列车的完整经停站顺序和时间。
     *
     * @param trainId 余票查询返回的列车标识
     * @param meta Agent 签名的 MCP 元数据
     * @return 列车经停站列表
     */
    @McpTool(
            name = "query_train_stops",
            description = "根据 query_tickets 返回的 trainId 查询该列车沿途经停站、到发时间和停留时长。",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "查询列车经停站",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public List<TrainStop> queryTrainStops(
            @McpToolParam(description = "query_tickets 返回的 trainId") String trainId,
            McpMeta meta) {
        // 列车标识仅允许稳定字符集，身份仍由签名元数据提供。
        requirePattern(trainId, "trainId", TRAIN_ID_PATTERN);
        McpCallerIdentity identity = authenticator.authenticate(meta);

        // 经停查询只读取列车运行数据，不触发库存或订单操作。
        return businessClient.queryTrainStops(trainId.trim(), identity);
    }

    /**
     * 查询当前登录用户的常用乘车人并确保敏感字段保持脱敏。
     *
     * @param meta Agent 签名的 MCP 元数据
     * @return 脱敏乘车人列表
     */
    @McpTool(
            name = "list_my_passengers",
            description = "查询当前登录用户的常用乘车人，只返回脱敏证件号和脱敏手机号。",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "查询我的乘车人",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public List<PassengerView> listMyPassengers(McpMeta meta) {
        // 用户身份不接受模型参数，只从经过 HMAC 校验的 MCP 元数据中取得。
        McpCallerIdentity identity = authenticator.authenticate(meta);

        // 下游响应经过字段白名单转换，不返回 actualIdCard 或 actualPhone。
        return businessClient.listPassengers(identity);
    }

    /**
     * 分页查询当前登录用户自己的历史订单。
     *
     * @param current 当前页，从 1 开始
     * @param size 每页数量，最大 20
     * @param meta Agent 签名的 MCP 元数据
     * @return 本人订单分页结果
     */
    @McpTool(
            name = "list_my_orders",
            description = "分页查询当前登录用户自己的车票订单，不允许查询其他用户。",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "查询我的订单",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public OrderPage listMyOrders(
            @McpToolParam(description = "当前页，从 1 开始") long current,
            @McpToolParam(description = "每页数量，范围 1 到 20") int size,
            McpMeta meta) {
        // 页码和页大小必须处于工具公开的固定范围内。
        Assert.isTrue(current >= 1, "current must be greater than or equal to 1");
        Assert.isTrue(size >= 1 && size <= 20, "size must be between 1 and 20");
        McpCallerIdentity identity = authenticator.authenticate(meta);

        // 订单服务根据已验证用户请求头限定数据归属。
        return businessClient.listOrders(current, size, identity);
    }

    /**
     * 执行已经由 Agent 数据库状态机确认并领取执行权的真实购票操作。
     *
     * @param actionId 已确认草案标识
     * @param trainId 车次内部标识
     * @param departure 出发站名称
     * @param arrival 到达站名称
     * @param passengers 当前用户乘车人与席别
     * @param chooseSeats 可选座位偏好
     * @param meta Agent 签名且包含草案和参数指纹的 MCP 元数据
     * @return 不包含证件信息的购票结果
     */
    @McpTool(
            name = "execute_confirmed_ticket_purchase",
            description = "仅供 Agent 确认状态机内部调用的真实购票工具，不允许回答模型直接调用。",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "执行已确认购票",
                    readOnlyHint = false,
                    destructiveHint = true,
                    idempotentHint = false,
                    openWorldHint = false))
    public ConfirmedPurchaseResult executeConfirmedPurchase(
            @McpToolParam(description = "已消费确认令牌的草案 ID") String actionId,
            @McpToolParam(description = "query_tickets 返回的 trainId") String trainId,
            @McpToolParam(description = "出发站完整名称") String departure,
            @McpToolParam(description = "到达站完整名称") String arrival,
            @McpToolParam(description = "乘车人 ID 与席别编码列表") List<ConfirmedPurchasePassenger> passengers,
            @McpToolParam(description = "座位偏好列表，没有偏好时为空数组") List<String> chooseSeats,
            McpMeta meta) {
        requirePattern(actionId, "actionId", TRAIN_ID_PATTERN);
        requirePattern(trainId, "trainId", TRAIN_ID_PATTERN);
        requireText(departure, "departure", MAX_STATION_NAME_LENGTH);
        requireText(arrival, "arrival", MAX_STATION_NAME_LENGTH);
        Assert.isTrue(!departure.trim().equals(arrival.trim()), "departure and arrival must differ");
        Assert.notEmpty(passengers, "passengers must not be empty");
        Assert.isTrue(passengers.size() <= 5, "passengers must not contain more than 5 items");
        Assert.notNull(chooseSeats, "chooseSeats must not be null");
        Assert.isTrue(chooseSeats.size() <= passengers.size(), "chooseSeats contains too many items");

        // 乘车人标识必须唯一且席别位于票务服务公开编码范围。
        Set<String> passengerIds = new HashSet<>();
        for (ConfirmedPurchasePassenger passenger : passengers) {
            Assert.notNull(passenger, "passenger must not be null");
            requirePattern(passenger.passengerId(), "passengerId", TRAIN_ID_PATTERN);
            Assert.isTrue(passengerIds.add(passenger.passengerId()), "passengerId must be unique");
            Assert.notNull(passenger.seatType(), "seatType must not be null");
            Assert.isTrue(passenger.seatType() >= 0 && passenger.seatType() <= 14,
                    "seatType must be between 0 and 14");
        }
        McpCallerIdentity identity = authenticator.authenticate(meta);

        // 草案标识和参数指纹都在 HMAC 元数据中，工具参数被替换时立即拒绝真实写调用。
        Assert.isTrue(actionId.equals(identity.actionId()), "actionId does not match signed metadata");
        PurchasePayloadProof payload = new PurchasePayloadProof(
                trainId.trim(), departure.trim(), arrival.trim(), List.copyOf(passengers), List.copyOf(chooseSeats));
        Assert.isTrue(fingerprint(payload).equals(identity.payloadHash()),
                "purchase payload does not match confirmed draft");

        // 身份和参数证明全部通过后才调用一次现有购票接口。
        return businessClient.purchase(
                payload.trainId(), payload.departure(), payload.arrival(),
                payload.passengers(), payload.chooseSeats(), identity);
    }

    /**
     * 校验必填文本的非空和最大长度。
     *
     * @param value 待校验文本
     * @param field 字段名
     * @param maxLength 最大长度
     */
    private void requireText(String value, String field, int maxLength) {
        // 文本边界在进入业务服务前统一收敛。
        Assert.hasText(value, field + " must not be blank");
        Assert.isTrue(value.trim().length() <= maxLength, field + " is too long");
    }

    /**
     * 校验必填标识是否符合允许字符集。
     *
     * @param value 待校验标识
     * @param field 字段名
     * @param pattern 允许的正则表达式
     */
    private void requirePattern(String value, String field, Pattern pattern) {
        // 先校验非空，再执行完整字符集匹配。
        Assert.hasText(value, field + " must not be blank");
        Assert.isTrue(pattern.matcher(value.trim()).matches(), field + " has invalid format");
    }

    /**
     * 解析严格的 ISO 本地日期并拒绝过去日期。
     *
     * @param value yyyy-MM-dd 日期文本
     * @return 解析后的日期
     */
    private LocalDate parseDate(String value) {
        // 票务查询只接受明确日期，避免模型输出含时区时间戳造成日期偏移。
        Assert.hasText(value, "departureDate must not be blank");
        try {
            LocalDate parsed = LocalDate.parse(value);
            Assert.isTrue(!parsed.isBefore(LocalDate.now()), "departureDate must not be in the past");
            return parsed;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("departureDate must use yyyy-MM-dd format", ex);
        }
    }

    /**
     * 计算与 Agent 草案端一致的规范购票参数指纹。
     *
     * @param payload 规范购票参数
     * @return SHA-256 十六进制指纹
     */
    private String fingerprint(PurchasePayloadProof payload) {
        try {
            // 两端都按同名记录字段序列化，确保确认后任何参数变化都会导致指纹不一致。
            byte[] json = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to verify confirmed purchase payload", ex);
        }
    }

    /**
     * @param trainId 车次内部标识
     * @param departure 出发站名称
     * @param arrival 到达站名称
     * @param passengers 乘车人与席别
     * @param chooseSeats 座位偏好
     */
    private record PurchasePayloadProof(
            String trainId,
            String departure,
            String arrival,
            List<ConfirmedPurchasePassenger> passengers,
            List<String> chooseSeats) {
    }
}
