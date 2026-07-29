package org.opengoofy.index12306.ai.agentservice.action.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePassenger;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在消费购票确认令牌前重新查询车次和余票，拒绝已经失效的草案快照。
 */
@Service
public class PurchaseDraftRevalidator {

    private final ObjectProvider<ToolCallbackProvider> callbackProviders;
    private final McpToolContextFactory toolContextFactory;
    private final ObjectMapper objectMapper;

    /**
     * 创建购票草案重新核验服务。
     *
     * @param callbackProviders 已启用的 MCP 工具提供器
     * @param toolContextFactory 服务端身份上下文工厂
     * @param objectMapper 工具参数和结果 JSON 解析器
     */
    public PurchaseDraftRevalidator(
            ObjectProvider<ToolCallbackProvider> callbackProviders,
            McpToolContextFactory toolContextFactory,
            ObjectMapper objectMapper) {
        this.callbackProviders = callbackProviders;
        this.toolContextFactory = toolContextFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 重新查询草案对应车次，并确认每种席别仍有足够余票。
     *
     * @param payload 已持久化且规范化的购票草案
     * @param context 当前确认请求上下文
     */
    public void revalidate(
            PurchasePayload payload,
            AgentRequestContext context) {
        try {
            // 站点编码始终由最新站点服务结果生成，不能从旧草案或模型文本推断。
            JsonNode departureStation = uniqueStation(
                    call("resolve_station", Map.of("keyword", payload.departure()), context),
                    payload.departure());
            JsonNode arrivalStation = uniqueStation(
                    call("resolve_station", Map.of("keyword", payload.arrival()), context),
                    payload.arrival());
            if (departureStation == null || arrivalStation == null) {
                throw unavailable("确认前无法唯一解析出发站或到达站，请稍后重试");
            }
            String departureCode = text(departureStation, "code");
            String arrivalCode = text(arrivalStation, "code");
            if (!StringUtils.hasText(departureCode) || !StringUtils.hasText(arrivalCode)) {
                throw unavailable("确认前站点服务未返回有效编码，请稍后重试");
            }

            // 每次确认都重新查询余票，确认卡片有效期内的库存变化也不能被忽略。
            JsonNode tickets = readJson(call(
                    "query_tickets",
                    Map.of(
                            "fromStationCode", departureCode,
                            "toStationCode", arrivalCode,
                            "departure", payload.departure(),
                            "arrival", payload.arrival(),
                            "departureDate", payload.departureDate()),
                    context));
            JsonNode train = findTrain(tickets.path("trains"), payload);
            if (train == null || !hasRequiredSeats(train, payload.passengers())) {
                throw stale();
            }
        } catch (AgentChatException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 网络、工具或 JSON 协议异常都不能消费确认令牌，用户可稍后重试同一草案。
            throw unavailable("确认前暂时无法核验最新余票，请稍后重试");
        }
    }

    /**
     * 从最新查票结果中定位草案原先展示的同一车次和停靠站。
     *
     * @param trains 最新车次集合
     * @param payload 已确认展示的草案参数
     * @return 同一车次；不存在时为 null
     */
    private JsonNode findTrain(
            JsonNode trains,
            PurchasePayload payload) {
        if (!trains.isArray()) {
            return null;
        }
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode train : trains) {
            if (payload.trainId().equals(text(train, "trainId"))
                    && payload.departure().equals(text(train, "departure"))
                    && payload.arrival().equals(text(train, "arrival"))) {
                matches.add(train);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * 校验每种席别最新余票不少于草案中的对应乘车人数。
     *
     * @param train 最新车次记录
     * @param passengers 草案中的乘车人与席别
     * @return 所有席别余票均充足时为 true
     */
    private boolean hasRequiredSeats(
            JsonNode train,
            List<PurchasePassenger> passengers) {
        Map<Integer, Integer> requiredBySeatType = new HashMap<>();
        for (PurchasePassenger passenger : passengers) {
            requiredBySeatType.merge(passenger.seatType(), 1, Integer::sum);
        }

        // 逐一核对草案中出现的席别，缺少席别记录等同于无可售余票。
        for (Map.Entry<Integer, Integer> required : requiredBySeatType.entrySet()) {
            int available = 0;
            for (JsonNode seat : train.path("seats")) {
                if (seat.path("type").asInt(-1) == required.getKey()) {
                    available = seat.path("quantity").asInt(0);
                    break;
                }
            }
            if (available < required.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 直接调用固定只读 MCP 工具并注入服务端身份上下文。
     *
     * @param toolName 固定工具名称
     * @param arguments 服务端生成的工具参数
     * @param context 当前确认请求上下文
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
                .orElseThrow(() -> new IllegalStateException("购票核验工具不可用：" + toolName));
        // 该调用发生在服务端确认链中，工具定义和参数均不会进入任何模型。
        return callback.call(
                writeJson(arguments),
                new ToolContext(toolContextFactory.create(context)));
    }

    /**
     * 从站点候选中选择与草案站名唯一匹配的结果。
     *
     * @param stationResult 站点工具 JSON
     * @param requestedName 草案中的站点名称
     * @return 唯一站点；无法唯一确定时为 null
     */
    private JsonNode uniqueStation(
            String stationResult,
            String requestedName) {
        JsonNode stations = readJson(stationResult);
        if (!stations.isArray()) {
            return null;
        }
        List<JsonNode> exactMatches = new ArrayList<>();
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
     * 规范化站名中的空白和可选“站”后缀。
     *
     * @param value 原始站名
     * @return 可用于唯一比对的站名
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
     * 解析工具返回的 JSON。
     *
     * @param value JSON 文本
     * @return JSON 根节点
     */
    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析购票核验结果", exception);
        }
    }

    /**
     * 序列化固定链工具参数。
     *
     * @param value 待序列化参数
     * @return JSON 文本
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成购票核验参数", exception);
        }
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

    /**
     * 创建余票或车次已经变化的冲突异常。
     *
     * @return 不会消费确认令牌的业务冲突
     */
    private AgentChatException stale() {
        return new AgentChatException(
                HttpStatus.CONFLICT,
                "PURCHASE_DRAFT_STALE",
                "车次或余票状态已经变化，请重新查询并生成购票草案");
    }

    /**
     * 创建确认前无法完成最新状态核验的服务异常。
     *
     * @param message 用户可执行的安全提示
     * @return 不会消费确认令牌的临时服务异常
     */
    private AgentChatException unavailable(String message) {
        return new AgentChatException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PURCHASE_REVALIDATION_UNAVAILABLE",
                message);
    }
}
