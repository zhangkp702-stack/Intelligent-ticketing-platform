package org.opengoofy.index12306.ai.agentservice.chat.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据只读意图直接调用固定 MCP 链路，不向任何模型注册工具。
 */
@Service
public class ReadTaskChain {

    private final ObjectProvider<ToolCallbackProvider> callbackProviders;
    private final McpToolContextFactory toolContextFactory;
    private final ObjectMapper objectMapper;

    /**
     * 创建只读任务固定链服务。
     *
     * @param callbackProviders 已启用的 MCP 工具提供器
     * @param toolContextFactory 服务端身份上下文工厂
     * @param objectMapper 工具参数和结果 JSON 解析器
     */
    public ReadTaskChain(
            ObjectProvider<ToolCallbackProvider> callbackProviders,
            McpToolContextFactory toolContextFactory,
            ObjectMapper objectMapper) {
        this.callbackProviders = callbackProviders;
        this.toolContextFactory = toolContextFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 在弹性线程池中执行当前只读任务对应的固定阻塞工具链。
     *
     * @param context 当前请求身份和审计上下文
     * @param task 已校验的只读任务
     * @param dependencyResults 当前任务显式声明的前置结果
     * @return 当前任务的异步结构化结果
     */
    public Mono<TaskExecutionResult> execute(
            AgentRequestContext context,
            PlannedTask task,
            List<TaskExecutionResult> dependencyResults) {
        // MCP 同步回调可能阻塞网络线程，统一切换到 boundedElastic 后再执行。
        return Mono.fromCallable(() -> executeBlocking(context, task, dependencyResults))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 根据受控意图进入唯一固定只读链路。
     *
     * @param context 当前请求上下文
     * @param task 当前子任务
     * @param dependencyResults 已完成的显式依赖结果
     * @return 不含模型生成文本的任务结果
     */
    private TaskExecutionResult executeBlocking(
            AgentRequestContext context,
            PlannedTask task,
            List<TaskExecutionResult> dependencyResults) {
        if ((!task.missingFields().isEmpty() || !task.unresolvedReferences().isEmpty())
                && dependencyResults.isEmpty()) {
            // 没有前置结果可补全时直接返回待补充状态，不调用任何外部工具。
            return needsInput(task, task.missingFields(), "请补充当前查询缺少的信息。");
        }

        return switch (task.intent()) {
            case GENERAL_CHAT -> success(task, "{\"type\":\"GENERAL_CHAT\"}");
            case TRAIN_QUERY -> executeTrainQuery(context, task);
            case TRAIN_STOP_QUERY -> executeTrainStopQuery(context, task, dependencyResults);
            case PASSENGER_QUERY -> executePassengerQuery(context, task);
            case ORDER_QUERY -> executeOrderQuery(context, task);
            case PAYMENT_QUERY -> executePaymentQuery(context, task);
            case TICKET_PURCHASE, ORDER_CANCELLATION, TICKET_REFUND ->
                    throw new IllegalArgumentException("交易意图不能进入只读固定链");
        };
    }

    /**
     * 固定执行出发站解析、到达站解析和一次余票查询。
     *
     * @param context 当前请求上下文
     * @param task 当前余票查询任务
     * @return 余票工具原始结构化结果
     */
    private TaskExecutionResult executeTrainQuery(
            AgentRequestContext context,
            PlannedTask task) {
        TaskSlots slots = task.slots();
        if (!task.missingFields().isEmpty()) {
            return needsInput(task, task.missingFields(), "查询余票需要明确出发地、目的地和日期。");
        }

        // 两个站点分别解析并只接受唯一候选，站点编码绝不从模型文本推断。
        JsonNode departureStation = uniqueStation(call(
                "resolve_station", Map.of("keyword", slots.departure()), context), slots.departure());
        JsonNode arrivalStation = uniqueStation(call(
                "resolve_station", Map.of("keyword", slots.arrival()), context), slots.arrival());
        if (departureStation == null || arrivalStation == null) {
            return needsInput(task, List.of("departure", "arrival"), "未能唯一确定出发站或到达站，请提供准确站名。");
        }
        String departureCode = text(departureStation, "code");
        String arrivalCode = text(arrivalStation, "code");
        if (!StringUtils.hasText(departureCode) || !StringUtils.hasText(arrivalCode)) {
            throw new IllegalStateException("站点解析结果缺少编码");
        }

        // 余票查询始终只调用一次，并使用服务端解析得到的站点编码。
        String result = call(
                "query_tickets",
                Map.of(
                        "fromStationCode", departureCode,
                        "toStationCode", arrivalCode,
                        "departure", slots.departure(),
                        "arrival", slots.arrival(),
                        "departureDate", slots.departureDate()),
                context);
        return success(task, result);
    }

    /**
     * 使用前置余票结果中的内部列车标识查询经停站。
     *
     * @param context 当前请求上下文
     * @param task 当前经停查询任务
     * @param dependencyResults 当前任务依赖的余票结果
     * @return 经停站工具结果或待补充状态
     */
    private TaskExecutionResult executeTrainStopQuery(
            AgentRequestContext context,
            PlannedTask task,
            List<TaskExecutionResult> dependencyResults) {
        String trainId = findTrainId(dependencyResults, task.slots().trainNumber());
        if (!StringUtils.hasText(trainId)) {
            // 经停工具只接受余票接口返回的内部标识，不能把用户输入的车次号直接当作 trainId。
            return needsInput(
                    task,
                    List.of("trainId"),
                    "需要先查询该车次对应行程，才能取得经停查询所需的列车标识。");
        }
        return success(task, call("query_train_stops", Map.of("trainId", trainId), context));
    }

    /**
     * 固定执行全部乘车人查询或按姓名逐个精确查询。
     *
     * @param context 当前请求上下文
     * @param task 当前乘车人查询任务
     * @return 脱敏乘车人结果
     */
    private TaskExecutionResult executePassengerQuery(
            AgentRequestContext context,
            PlannedTask task) {
        List<String> names = task.slots().passengerNames();
        if (names.isEmpty()) {
            return success(task, call("list_my_passengers", Map.of(), context));
        }

        // 多个姓名分别调用精确查询，结果按用户提供姓名顺序写入同一 JSON 数组。
        ArrayNode results = objectMapper.createArrayNode();
        for (String name : names) {
            JsonNode passengers = readJson(call(
                    "find_my_passengers_by_name", Map.of("realName", name), context));
            results.add(objectMapper.createObjectNode()
                    .put("realName", name)
                    .set("passengers", passengers));
        }
        return success(task, writeJson(results));
    }

    /**
     * 固定执行本人订单列表或指定订单详情查询。
     *
     * @param context 当前请求上下文
     * @param task 当前订单查询任务
     * @return 本人订单结构化结果
     */
    private TaskExecutionResult executeOrderQuery(
            AgentRequestContext context,
            PlannedTask task) {
        String orderSn = task.slots().orderSn();
        if (StringUtils.hasText(orderSn)) {
            return success(task, call(
                    "get_my_order_detail", Map.of("orderSn", orderSn), context));
        }
        return success(task, call(
                "list_my_orders", Map.of("current", 1, "size", 20), context));
    }

    /**
     * 固定执行指定本人订单的支付状态查询。
     *
     * @param context 当前请求上下文
     * @param task 当前支付状态任务
     * @return 支付状态结果或待补充状态
     */
    private TaskExecutionResult executePaymentQuery(
            AgentRequestContext context,
            PlannedTask task) {
        String orderSn = task.slots().orderSn();
        if (!StringUtils.hasText(orderSn)) {
            return needsInput(task, List.of("orderSn"), "查询支付状态需要明确订单号。");
        }
        return success(task, call("query_pay_status", Map.of("orderSn", orderSn), context));
    }

    /**
     * 直接调用指定只读 MCP 工具并注入服务端身份上下文。
     *
     * @param toolName 固定工具名称
     * @param arguments 固定链生成的参数
     * @param context 当前请求上下文
     * @return 工具 JSON 结果
     */
    private String call(
            String toolName,
            Map<String, Object> arguments,
            AgentRequestContext context) {
        ToolCallback callback = callbackProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .filter(candidate -> toolName.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("只读查询工具暂时不可用：" + toolName));
        // 参数由服务端固定链生成，模型不能看到或修改 ToolCallback。
        return callback.call(writeJson(arguments), new ToolContext(toolContextFactory.create(context)));
    }

    /**
     * 从站点候选中选择与用户名称唯一匹配的安全结果。
     *
     * @param stationResult 站点工具 JSON
     * @param requestedName 用户提供的站点名称
     * @return 唯一站点；无法唯一确定时为 null
     */
    private JsonNode uniqueStation(
            String stationResult,
            String requestedName) {
        JsonNode stations = readJson(stationResult);
        if (!stations.isArray()) {
            return null;
        }
        List<JsonNode> exactMatches = new java.util.ArrayList<>();
        for (JsonNode station : stations) {
            if (normalizeStationName(requestedName).equals(
                    normalizeStationName(text(station, "name")))) {
                exactMatches.add(station);
            }
        }
        if (exactMatches.size() == 1) {
            return exactMatches.get(0);
        }
        return stations.size() == 1 ? stations.get(0) : null;
    }

    /**
     * 从显式依赖的余票结果中查找指定车次的内部标识。
     *
     * @param dependencyResults 已成功完成的依赖结果
     * @param trainNumber 用户指定的车次号
     * @return 唯一匹配的 trainId；无法确定时为 null
     */
    private String findTrainId(
            List<TaskExecutionResult> dependencyResults,
            String trainNumber) {
        if (!StringUtils.hasText(trainNumber)) {
            return null;
        }
        List<String> matches = new java.util.ArrayList<>();
        for (TaskExecutionResult dependency : dependencyResults) {
            JsonNode trains = readJson(dependency.content()).path("trains");
            if (!trains.isArray()) {
                continue;
            }
            for (JsonNode train : trains) {
                if (trainNumber.equalsIgnoreCase(text(train, "trainNumber"))
                        && StringUtils.hasText(text(train, "trainId"))) {
                    matches.add(text(train, "trainId"));
                }
            }
        }
        return matches.stream().distinct().count() == 1 ? matches.get(0) : null;
    }

    /**
     * 创建成功的只读任务结果。
     *
     * @param task 当前任务
     * @param content 固定链结构化结果
     * @return 成功结果
     */
    private TaskExecutionResult success(
            PlannedTask task,
            String content) {
        return new TaskExecutionResult(
                task.taskId(),
                task.sequence(),
                task.intent(),
                TaskExecutionStatus.SUCCESS,
                task.standaloneQuestion(),
                content,
                List.of(),
                null,
                null);
    }

    /**
     * 创建等待用户补充信息的任务结果。
     *
     * @param task 当前任务
     * @param missingFields 缺失字段
     * @param content 安全提示
     * @return 待补充结果
     */
    private TaskExecutionResult needsInput(
            PlannedTask task,
            List<String> missingFields,
            String content) {
        return new TaskExecutionResult(
                task.taskId(),
                task.sequence(),
                task.intent(),
                TaskExecutionStatus.NEEDS_INPUT,
                task.standaloneQuestion(),
                content,
                List.copyOf(missingFields),
                null,
                null);
    }

    /**
     * 解析工具返回的 JSON。
     *
     * @param value JSON 文本
     * @return JSON 节点
     */
    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析固定链工具结果", exception);
        }
    }

    /**
     * 序列化固定链参数或聚合结果。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成固定链 JSON", exception);
        }
    }

    /**
     * 规范化站名用于唯一候选比对。
     *
     * @param value 原始站名
     * @return 去除空白和“站”后缀后的名称
     */
    private String normalizeStationName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", "").trim();
        return normalized.endsWith("站")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    /**
     * 从 JSON 对象读取文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 字段文本或 null
     */
    private String text(
            JsonNode node,
            String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
