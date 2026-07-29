package org.opengoofy.index12306.ai.agentservice.workflow.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.action.mcp.PurchaseDraftTools;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.PurchaseIntentData;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PurchaseWorkflowContext;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.PassengerResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.opengoofy.index12306.ai.agentservice.workflow.mcp.PurchasePassengerTools;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 以确定顺序执行信息齐全的购票调用链，避免回答模型自行决定关键工具是否调用。
 */
@Service
public class PurchaseChainExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(PurchaseChainExecutor.class);
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("20\\d{2}-\\d{2}-\\d{2}");
    private static final Pattern TIME_PATTERN = Pattern.compile("[0-2]\\d:[0-5]\\d");

    private final ObjectProvider<ToolCallbackProvider> callbackProviders;
    private final McpToolContextFactory toolContextFactory;
    private final PurchasePassengerTools passengerTools;
    private final PurchaseDraftTools purchaseDraftTools;
    private final PurchaseWorkflowService purchaseWorkflowService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建购票确定性调用链。
     *
     * @param callbackProviders MCP 工具提供器
     * @param toolContextFactory 服务端身份上下文工厂
     * @param passengerTools 按姓名查询并解析乘车人的本地工具
     * @param purchaseDraftTools 创建待确认购票草案的本地工具
     * @param purchaseWorkflowService 购票工作流状态服务
     * @param objectMapper MCP 响应解析器
     * @param clock 统一日期时钟
     */
    public PurchaseChainExecutor(
            ObjectProvider<ToolCallbackProvider> callbackProviders,
            McpToolContextFactory toolContextFactory,
            PurchasePassengerTools passengerTools,
            PurchaseDraftTools purchaseDraftTools,
            PurchaseWorkflowService purchaseWorkflowService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.callbackProviders = callbackProviders;
        this.toolContextFactory = toolContextFactory;
        this.passengerTools = passengerTools;
        this.purchaseDraftTools = purchaseDraftTools;
        this.purchaseWorkflowService = purchaseWorkflowService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在用户已给出行程、乘车人、席别和可唯一定位车次的条件下直接执行购票草案链路。
     *
     * @param context 当前服务端请求上下文
     * @param extracted 模型在意图识别时从用户原话返回的结构化购票字段
     * @return 固定购票链路的结果；字段不全时返回需要补充的信息
     */
    public PurchaseChainResult execute(AgentRequestContext context, PurchaseIntentData extracted) {
        PurchaseWorkflowContext readyContext = purchaseWorkflowService
                .findReadyDraftContext(context.userId(), context.conversationId())
                .orElse(null);
        if (readyContext != null) {
            // 前端完成乘车人选择后直接消费数据库中的可信上下文，不再要求模型重复提取整段行程。
            prepareReadyDraft(context, readyContext);
            return new PurchaseChainResult("已按您提供的信息生成购票草案，请核对后确认下单。");
        }

        ExtractionResult extraction = validateExtractedRequest(extracted);
        if (extraction.request() == null) {
            return new PurchaseChainResult("购票信息不完整：缺少" + String.join("、", extraction.missingFields()) + "。");
        }
        PurchaseRequest request = extraction.request();

        // 所有固定步骤开始前一次性校验依赖，避免只查到余票后才发现乘车人查询工具缺失。
        ensureRequiredToolsAvailable();

        // 先按用户原文分别解析出发站和到达站，名称不唯一时不猜测编码。
        JsonNode departureStation = uniqueStation(call("resolve_station", Map.of("keyword", request.departure()), context), request.departure());
        JsonNode arrivalStation = uniqueStation(call("resolve_station", Map.of("keyword", request.arrival()), context), request.arrival());
        if (departureStation == null || arrivalStation == null) {
            return new PurchaseChainResult("未能唯一确定出发站或到达站，请补充准确站名。");
        }
        String departureStationCode = text(departureStation, "code");
        String arrivalStationCode = text(arrivalStation, "code");
        if (!StringUtils.hasText(departureStationCode) || !StringUtils.hasText(arrivalStationCode)) {
            // 站点匹配成功但响应缺少编码时不能继续构造 Map.of，避免空值导致整轮对话异常。
            LOGGER.warn("Purchase-chain station code missing, requestId={}, departureCodePresent={}, arrivalCodePresent={}",
                    context.requestId(), StringUtils.hasText(departureStationCode), StringUtils.hasText(arrivalStationCode));
            return new PurchaseChainResult("站点查询未返回可用编码，请稍后重试。");
        }

        // 余票查询依赖已解析的站点编码，不能由模型或正则伪造编码。
        JsonNode tickets = call("query_tickets", Map.of(
                "fromStationCode", departureStationCode,
                "toStationCode", arrivalStationCode,
                "departure", request.departure(),
                "arrival", request.arrival(),
                "departureDate", request.departureDate().toString()), context);
        if (!StringUtils.hasText(request.trainNumber()) && !StringUtils.hasText(request.departureTime())) {
            // 用户未指定车次时先返回满足席别条件的真实候选项，不能把“车次或时间”当成必填文本字段。
            return new PurchaseChainResult(trainSelectionMessage(tickets.path("trains"), request.seatClass()));
        }
        JsonNode train = selectTrain(tickets.path("trains"), request);
        if (train == null) {
            return new PurchaseChainResult("没有找到符合车次、时间和席别条件且有余票的列车。");
        }

        // 下单必须使用余票查询返回的实际停靠站，不能继续使用用户输入的城市简称。
        String selectedTrainId = text(train, "trainId");
        String selectedDeparture = text(train, "departure");
        String selectedArrival = text(train, "arrival");
        if (!StringUtils.hasText(selectedTrainId)
                || !StringUtils.hasText(selectedDeparture)
                || !StringUtils.hasText(selectedArrival)) {
            return new PurchaseChainResult("余票查询结果缺少车次或实际停靠站信息，请稍后重试。");
        }

        // 乘车人查询固定发生在余票车次选定后，并且只查询用户给出的姓名。
        PassengerResolutionResult passengerResult = passengerTools.resolvePurchasePassengers(
                selectedTrainId, selectedDeparture, selectedArrival, request.departureDate().toString(),
                request.passengerNames(), request.seatClass(), toolContext(context));
        if (passengerResult.status() == PassengerResolutionStatus.NOT_FOUND) {
            return new PurchaseChainResult("当前账号下未找到“" + String.join("、", request.passengerNames()) + "”对应的乘车人。");
        }
        if (passengerResult.status() != PassengerResolutionStatus.RESOLVED) {
            return new PurchaseChainResult(passengerResult.message());
        }

        // 只有服务端已经精确匹配得到乘车人 ID 后，才创建待确认草案。
        purchaseDraftTools.prepareTicketPurchase(
                selectedTrainId, selectedDeparture, selectedArrival, request.departureDate().toString(),
                passengerResult.resolvedPassengers().stream()
                        .map(passenger -> new PurchaseDraftTools.PassengerDraftInput(
                                passenger.passengerId(), request.seatClass()))
                        .toList(),
                List.of(), toolContext(context));
        return new PurchaseChainResult("已按您提供的信息生成购票草案，请核对后确认下单。");
    }

    /**
     * 使用已经完成乘车人和席别选择的服务端工作流上下文创建购票草案。
     *
     * @param context 当前服务端请求上下文
     * @param workflowContext 数据库中已推进到草案阶段的购票上下文
     */
    private void prepareReadyDraft(
            AgentRequestContext context,
            PurchaseWorkflowContext workflowContext) {
        PurchaseSeatClass seatClass = PurchaseSeatClass.fromCode(workflowContext.seatType());
        // 乘车人标识、行程和席别全部来自数据库工作流，当前对话只能触发固定草案创建。
        purchaseDraftTools.prepareTicketPurchase(
                workflowContext.trainId(),
                workflowContext.departure(),
                workflowContext.arrival(),
                workflowContext.departureDate(),
                workflowContext.selectedPassengerIds().stream()
                        .map(passengerId -> new PurchaseDraftTools.PassengerDraftInput(
                                passengerId, seatClass))
                        .toList(),
                workflowContext.chooseSeats(),
                toolContext(context));
    }

    /**
     * 校验意图模型返回的字段，并转换为固定购票调用链可消费的类型。
     *
     * @param extracted 模型返回的购票字段
     * @return 有效请求或缺失字段列表
     */
    private ExtractionResult validateExtractedRequest(PurchaseIntentData extracted) {
        List<String> missing = new java.util.ArrayList<>();
        if (extracted == null || !StringUtils.hasText(extracted.departure())) {
            missing.add("出发站");
        }
        if (extracted == null || !StringUtils.hasText(extracted.arrival())) {
            missing.add("到达站");
        }
        LocalDate departureDate = extracted == null ? null : departureDate(extracted.departureDate());
        if (departureDate == null) {
            missing.add("乘车日期");
        }
        PurchaseSeatClass seatClass = extracted == null ? null : seatClass(extracted.seatClass());
        if (seatClass == null) {
            missing.add("席别");
        }
        List<String> passengerNames = extracted == null || extracted.passengerNames() == null ? List.of()
                : extracted.passengerNames().stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (passengerNames.isEmpty()) {
            missing.add("乘车人");
        }
        String trainNumber = extracted == null ? null : trimToNull(extracted.trainNumber());
        String departureTime = extracted == null ? null : normalizeTime(extracted.departureTime());
        if (!missing.isEmpty()) {
            return new ExtractionResult(null, List.copyOf(missing));
        }
        return new ExtractionResult(new PurchaseRequest(
                extracted.departure().trim(), extracted.arrival().trim(), passengerNames, departureDate,
                seatClass, trainNumber, departureTime), List.of());
    }

    /**
     * 根据用户表达的相对或绝对日期确定乘车日期。
     *
     * @param question 用户原始购票请求
     * @return 乘车日期；无法确定时返回 null
     */
    private LocalDate departureDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        LocalDate today = LocalDate.now(clock);
        // 相对日期统一以服务端时钟计算，避免客户端时区造成日期漂移。
        if (value.contains("明天")) {
            return today.plusDays(1);
        }
        if (value.contains("后天")) {
            return today.plusDays(2);
        }
        if (value.contains("今天")) {
            return today;
        }
        if (!ISO_DATE_PATTERN.matcher(value).matches()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    /**
     * 根据用户表达映射语义席别。
     *
     * @param question 用户原始购票请求
     * @return 已识别席别；没有明确席别时返回 null
     */
    private PurchaseSeatClass seatClass(String value) {
        // 先匹配更具体的席别，避免“二等座”被“座”之类的短文本误判。
        return Arrays.stream(PurchaseSeatClass.values())
                .filter(candidate -> candidate.label().equals(value) || candidate.name().equals(value))
                .findFirst()
                .orElse(null);
    }

    /**
     * 从站点候选中取得与用户站名完全一致的唯一记录。
     *
     * @param stations MCP 返回的站点数组
     * @param stationName 用户提供的完整站名
     * @return 唯一匹配站点；不存在或有歧义时返回 null
     */
    private JsonNode uniqueStation(JsonNode stations, String stationName) {
        if (!stations.isArray()) {
            return null;
        }
        List<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode station : stations) {
            if (stationName.equals(text(station, "name"))) {
                matches.add(station);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }

        // 分类模型可能保留“站”后缀，站点服务可能省略该后缀；只在规范化后仍唯一时才接受。
        String normalizedStationName = normalizeStationName(stationName);
        matches.clear();
        for (JsonNode station : stations) {
            if (normalizedStationName.equals(normalizeStationName(text(station, "name")))) {
                matches.add(station);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }

        // 查询关键词仅返回一个候选时，该候选已由站点服务完成消歧，可直接使用其编码。
        return stations.size() == 1 ? stations.get(0) : null;
    }

    /**
     * 规范化站名中的空白和可选“站”后缀，用于与站点服务结果做唯一比对。
     *
     * @param stationName 原始站名
     * @return 用于精确比对的站名；空值时返回空字符串
     */
    private String normalizeStationName(String stationName) {
        if (!StringUtils.hasText(stationName)) {
            return "";
        }
        String normalized = stationName.replaceAll("\\s+", "").trim();
        return normalized.endsWith("站") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    /**
     * 在余票结果中选择与用户车次或出发时间匹配且指定席别有余票的唯一列车。
     *
     * @param trains MCP 返回的列车数组
     * @param request 已提取的购票请求
     * @return 可安全用于创建草案的列车；不能唯一确定时返回 null
     */
    private JsonNode selectTrain(JsonNode trains, PurchaseRequest request) {
        List<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode train : trains) {
            boolean trainMatches = !StringUtils.hasText(request.trainNumber())
                    || request.trainNumber().equalsIgnoreCase(text(train, "trainNumber"));
            boolean timeMatches = !StringUtils.hasText(request.departureTime())
                    || request.departureTime().equals(text(train, "departureTime"));
            boolean seatAvailable = false;
            for (JsonNode seat : train.path("seats")) {
                if (seat.path("type").asInt(-1) == request.seatClass().code()
                        && seat.path("quantity").asInt(0) > 0) {
                    seatAvailable = true;
                    break;
                }
            }
            if (trainMatches && timeMatches && seatAvailable) {
                matches.add(train);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * 将指定席别仍有余票的列车转换为用户可直接回复选择的候选列表。
     *
     * @param trains 余票查询返回的列车集合
     * @param seatClass 用户已明确的席别
     * @return 不含内部标识和敏感信息的候选提示
     */
    private String trainSelectionMessage(JsonNode trains, PurchaseSeatClass seatClass) {
        List<String> candidates = new java.util.ArrayList<>();
        for (JsonNode train : trains) {
            int quantity = 0;
            for (JsonNode seat : train.path("seats")) {
                if (seat.path("type").asInt(-1) == seatClass.code()) {
                    quantity = seat.path("quantity").asInt(0);
                    break;
                }
            }
            if (quantity > 0) {
                candidates.add(text(train, "trainNumber") + "（"
                        + text(train, "departureTime") + " 出发，"
                        + seatClass.label() + "余票 " + quantity + " 张）");
            }
        }
        if (candidates.isEmpty()) {
            return "该日期和区间没有可售的" + seatClass.label() + "车次。";
        }
        return "请选择本次购票的车次：\n" + String.join("\n", candidates)
                + "\n回复车次号即可继续查询乘车人并生成购票草案。";
    }

    /**
     * 标准化模型已经提取出的出发时间，拒绝不符合工具协议的值。
     *
     * @param value 模型返回的时间文本
     * @return HH:mm 格式时间；为空或非法时返回 null
     */
    private String normalizeTime(String value) {
        String normalized = trimToNull(value);
        return normalized != null && TIME_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    /**
     * 清理可选文本字段的首尾空白。
     *
     * @param value 原始文本
     * @return 非空文本；空白时返回 null
     */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 使用服务端身份上下文调用指定 MCP 只读工具。
     *
     * @param toolName MCP 工具名称
     * @param arguments 工具参数
     * @param context 当前请求上下文
     * @return 解析后的 JSON 响应
     */
    private JsonNode call(String toolName, Map<String, String> arguments, AgentRequestContext context) {
        ToolCallback callback = requiredTool(toolName);
        try {
            // 参数由固定链路构造，用户身份仍只能来自服务端 MCP 上下文。
            String response = callback.call(objectMapper.writeValueAsString(arguments), toolContext(context));
            return objectMapper.readTree(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析购票链路工具响应", exception);
        }
    }

    /**
     * 校验本次确定性购票链路需要的 MCP 工具全部已经注册。
     */
    private void ensureRequiredToolsAvailable() {
        // 按姓名查询是代码链路内部依赖，不会暴露给回答模型，也不能遗漏可用性校验。
        requiredTool("resolve_station");
        requiredTool("query_tickets");
        requiredTool("find_my_passengers_by_name");
    }

    /**
     * 按名称获取已注册工具，缺失时给出确定性链路的明确错误。
     *
     * @param toolName 所需工具名称
     * @return 已注册的工具回调
     */
    private ToolCallback requiredTool(String toolName) {
        return callbackProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .filter(candidate -> toolName.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("购票链路缺少工具：" + toolName));
    }

    /**
     * 创建模型不可覆盖的服务端工具上下文。
     *
     * @param context 当前请求上下文
     * @return 用于本地和 MCP 工具的上下文
     */
    private ToolContext toolContext(AgentRequestContext context) {
        // 统一由工厂注入身份与轮次，调用链不会接受模型生成的用户标识。
        return new ToolContext(toolContextFactory.create(context));
    }

    /**
     * 从 JSON 对象读取允许为空的文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名称
     * @return 字段值；不存在时返回 null
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 购票链路执行后的用户可读正文。
     *
     * @param message 可持久化的结果文本
     */
    public record PurchaseChainResult(String message) {
    }

    /**
     * 已从用户请求中确定的购票字段。
     */
    private record PurchaseRequest(
            String departure,
            String arrival,
            List<String> passengerNames,
            LocalDate departureDate,
            PurchaseSeatClass seatClass,
            String trainNumber,
            String departureTime) {
    }

    /**
     * 模型字段校验后的购票请求或缺失项。
     *
     * @param request 可执行的购票请求
     * @param missingFields 未提供或不符合协议的字段
     */
    private record ExtractionResult(PurchaseRequest request, List<String> missingFields) {
    }
}
