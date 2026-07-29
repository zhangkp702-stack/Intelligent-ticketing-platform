package org.opengoofy.index12306.ai.agentservice.chat.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TrainSelectionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 使用固定规则把前置查询结果解析为后续交易任务可消费的业务槽位。
 */
@Service
public class TaskDependencyResolver {

    private final ObjectMapper objectMapper;

    /**
     * 创建任务依赖结果解析器。
     *
     * @param objectMapper 查询结果 JSON 解析器
     */
    public TaskDependencyResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 根据购票任务的选车策略从显式依赖结果中确定唯一车次。
     *
     * @param task 当前已校验任务
     * @param dependencyResults 当前任务显式依赖的成功结果
     * @return 已补全车次的槽位，或不允许继续创建交易草案的安全说明
     */
    public DependencyResolution resolve(
            PlannedTask task,
            List<TaskExecutionResult> dependencyResults) {
        TrainSelectionPolicy policy = task.slots().selectionPolicy();
        if (task.intent() != AgentIntent.TICKET_PURCHASE || policy == null) {
            return DependencyResolution.success(task.slots(), null);
        }

        // 席别编码和乘车人数决定候选车次是否拥有足够余票。
        PurchaseSeatClass seatClass = seatClass(task.slots().seatClass());
        if (seatClass == null) {
            return DependencyResolution.failed("无法识别购票席别，请重新指定席别。");
        }
        int requiredQuantity = Math.max(1, task.slots().passengerNames().size());
        List<TrainCandidate> candidates = collectCandidates(
                dependencyResults, seatClass, requiredQuantity, policy);
        if (candidates.isEmpty()) {
            return DependencyResolution.failed(noCandidateMessage(policy, seatClass, requiredQuantity));
        }

        // 比较器包含稳定次级字段，相同时间或价格下仍得到可复现的唯一结果。
        candidates.sort(comparator(policy));
        TrainCandidate selected = candidates.get(0);
        TaskSlots source = task.slots();
        TaskSlots resolvedSlots = new TaskSlots(
                source.departure(),
                source.arrival(),
                source.departureDate(),
                selected.trainNumber(),
                selected.departureTime().toString(),
                source.seatClass(),
                null,
                source.passengerNames(),
                source.orderSn(),
                source.ridingDate());
        String summary = "已按“" + policyLabel(policy) + "”规则选择 "
                + selected.trainNumber() + "（" + selected.departureTime() + " 出发）。";
        return DependencyResolution.success(resolvedSlots, summary);
    }

    /**
     * 从成功的查票依赖中收集席别和人数均满足要求的候选车次。
     *
     * @param dependencyResults 当前任务依赖结果
     * @param seatClass 用户指定席别
     * @param requiredQuantity 所需票数
     * @param policy 当前选车策略
     * @return 可参与确定性排序的候选车次
     */
    private List<TrainCandidate> collectCandidates(
            List<TaskExecutionResult> dependencyResults,
            PurchaseSeatClass seatClass,
            int requiredQuantity,
            TrainSelectionPolicy policy) {
        List<TrainCandidate> candidates = new ArrayList<>();
        for (TaskExecutionResult dependency : dependencyResults) {
            if (dependency.intent() != AgentIntent.TRAIN_QUERY
                    || dependency.status() != TaskExecutionStatus.SUCCESS) {
                continue;
            }
            JsonNode trains = readJson(dependency.content()).path("trains");
            if (!trains.isArray()) {
                continue;
            }
            for (JsonNode train : trains) {
                TrainCandidate candidate = candidate(
                        train, seatClass, requiredQuantity, policy);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    /**
     * 将单个余票记录转换为可排序候选，不满足席别、数量或字段协议时忽略。
     *
     * @param train 单个余票车次
     * @param seatClass 用户指定席别
     * @param requiredQuantity 所需票数
     * @param policy 当前选车策略
     * @return 合法候选；当前记录不可安全选择时为 null
     */
    private TrainCandidate candidate(
            JsonNode train,
            PurchaseSeatClass seatClass,
            int requiredQuantity,
            TrainSelectionPolicy policy) {
        JsonNode selectedSeat = null;
        for (JsonNode seat : train.path("seats")) {
            if (seat.path("type").asInt(-1) == seatClass.code()
                    && seat.path("quantity").asInt(0) >= requiredQuantity) {
                selectedSeat = seat;
                break;
            }
        }
        if (selectedSeat == null) {
            return null;
        }

        String trainNumber = text(train, "trainNumber");
        String trainId = text(train, "trainId");
        LocalTime departureTime = time(text(train, "departureTime"));
        BigDecimal price = price(selectedSeat.path("price"));
        if (!StringUtils.hasText(trainNumber) || departureTime == null) {
            return null;
        }
        if (policy == TrainSelectionPolicy.CHEAPEST && price == null) {
            // 最低价策略必须拥有可信价格，不能把缺失价格当成零元。
            return null;
        }
        return new TrainCandidate(
                trainNumber,
                StringUtils.hasText(trainId) ? trainId : "",
                departureTime,
                price);
    }

    /**
     * 返回当前选车策略对应的稳定比较器。
     *
     * @param policy 当前选车策略
     * @return 包含稳定次级排序字段的比较器
     */
    private Comparator<TrainCandidate> comparator(TrainSelectionPolicy policy) {
        Comparator<TrainCandidate> stable = Comparator
                .comparing(TrainCandidate::trainNumber)
                .thenComparing(TrainCandidate::trainId);
        return switch (policy) {
            case EARLIEST -> Comparator
                    .comparing(TrainCandidate::departureTime)
                    .thenComparing(stable);
            case LATEST -> Comparator
                    .comparing(TrainCandidate::departureTime)
                    .reversed()
                    .thenComparing(stable);
            case CHEAPEST -> Comparator
                    .comparing(TrainCandidate::price)
                    .thenComparing(TrainCandidate::departureTime)
                    .thenComparing(stable);
        };
    }

    /**
     * 根据策略生成无可用候选时的用户提示。
     *
     * @param policy 当前选车策略
     * @param seatClass 用户指定席别
     * @param requiredQuantity 所需票数
     * @return 不包含内部字段的安全提示
     */
    private String noCandidateMessage(
            TrainSelectionPolicy policy,
            PurchaseSeatClass seatClass,
            int requiredQuantity) {
        if (policy == TrainSelectionPolicy.CHEAPEST) {
            return "查票结果中没有同时包含可信价格且余票充足的"
                    + seatClass.label() + "车次，无法自动选择最便宜车次。";
        }
        return "查票结果中没有余票不少于 " + requiredQuantity + " 张的"
                + seatClass.label() + "车次，无法按“" + policyLabel(policy) + "”规则继续购票。";
    }

    /**
     * 将策略转换为用户可读名称。
     *
     * @param policy 当前选车策略
     * @return 中文策略名称
     */
    private String policyLabel(TrainSelectionPolicy policy) {
        return switch (policy) {
            case EARLIEST -> "最早出发";
            case LATEST -> "最晚出发";
            case CHEAPEST -> "价格最低";
        };
    }

    /**
     * 将席别名称或枚举名转换为稳定票务编码。
     *
     * @param value 规划器提取的席别
     * @return 已识别席别；无法识别时为 null
     */
    private PurchaseSeatClass seatClass(String value) {
        return Arrays.stream(PurchaseSeatClass.values())
                .filter(candidate -> candidate.label().equals(value)
                        || candidate.name().equals(value))
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析固定链查票结果。
     *
     * @param value 查票 JSON 文本
     * @return JSON 根节点
     */
    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析依赖的查票结果", exception);
        }
    }

    /**
     * 解析 HH:mm 出发时间。
     *
     * @param value 出发时间文本
     * @return 可排序时间；格式非法时为 null
     */
    private LocalTime time(String value) {
        try {
            return StringUtils.hasText(value) ? LocalTime.parse(value) : null;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * 解析席别价格。
     *
     * @param value 价格 JSON 字段
     * @return 可比较价格；字段缺失或非法时为 null
     */
    private BigDecimal price(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            return null;
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
     * 依赖解析后的交易槽位和安全说明。
     *
     * @param slots 已补全槽位；解析失败时为 null
     * @param selectionSummary 自动选择说明；没有执行选择时为 null
     * @param failureMessage 阻止交易继续执行的原因；成功时为 null
     */
    public record DependencyResolution(
            TaskSlots slots,
            String selectionSummary,
            String failureMessage) {

        /**
         * 创建成功的依赖解析结果。
         *
         * @param slots 可供固定交易链使用的槽位
         * @param selectionSummary 可选的自动选择说明
         * @return 成功结果
         */
        public static DependencyResolution success(
                TaskSlots slots,
                String selectionSummary) {
            return new DependencyResolution(slots, selectionSummary, null);
        }

        /**
         * 创建阻止交易执行的依赖解析结果。
         *
         * @param failureMessage 用户可读失败原因
         * @return 失败结果
         */
        public static DependencyResolution failed(String failureMessage) {
            return new DependencyResolution(null, null, failureMessage);
        }

        /**
         * 判断是否已得到可执行交易槽位。
         *
         * @return 存在可执行槽位时为 true
         */
        public boolean resolved() {
            return slots != null;
        }
    }

    /**
     * 参与确定性排序的最小车次字段。
     *
     * @param trainNumber 对外车次号
     * @param trainId 内部车次标识，仅用于稳定次级排序
     * @param departureTime 出发时间
     * @param price 当前席别价格
     */
    private record TrainCandidate(
            String trainNumber,
            String trainId,
            LocalTime departureTime,
            BigDecimal price) {
    }
}
