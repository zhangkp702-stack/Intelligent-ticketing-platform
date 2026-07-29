package org.opengoofy.index12306.ai.agentservice.chat.planning;

import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TrainSelectionPolicy;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.WorkflowRelation;
import org.opengoofy.index12306.ai.agentservice.infra.model.structured.InvalidModelOutputException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对任务规划模型的输出执行确定性边界校验和字段规范化。
 */
@Component
public class TaskPlanValidator {

    private static final int MAX_TASKS = 5;
    private static final int MAX_TEXT_LENGTH = 1_000;
    private static final int MAX_LIST_ITEMS = 10;
    private static final Set<AgentIntent> TRANSACTION_INTENTS = Set.of(
            AgentIntent.TICKET_PURCHASE,
            AgentIntent.ORDER_CANCELLATION,
            AgentIntent.TICKET_REFUND);
    private static final TaskSlots EMPTY_SLOTS =
            new TaskSlots(null, null, null, null, null, null, null, List.of(), null, null);

    /**
     * 校验任务数量、字段、依赖图和交易边界，并返回可供后续调度的不可变计划。
     *
     * @param plan 结构化模型返回的原始任务计划
     * @return 已规范化且通过确定性边界校验的任务计划
     */
    public TaskPlan validate(TaskPlan plan) {
        // 模型结构先独立校验，交易数量属于服务端能力边界，不应触发候选模型重试。
        TaskPlan normalizedPlan = validateModelOutput(plan);
        validateTransactionCount(normalizedPlan.tasks());
        return normalizedPlan;
    }

    /**
     * 校验模型输出结构、字段和依赖图，供结构化调用在候选模型尝试内部使用。
     *
     * @param plan 结构化模型返回的原始任务计划
     * @return 已规范化且通过模型输出边界校验的任务计划
     */
    public TaskPlan validateModelOutput(TaskPlan plan) {
        if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
            throw invalid("任务计划不能为空");
        }
        if (plan.tasks().size() > MAX_TASKS) {
            throw invalid("单轮任务数量不能超过 " + MAX_TASKS);
        }

        // 先规范化每个任务，再使用规范化标识校验跨任务依赖关系。
        List<PlannedTask> normalizedTasks = plan.tasks().stream()
                .map(this::normalizeTask)
                .sorted(Comparator.comparingInt(PlannedTask::sequence))
                .toList();
        validateTaskIdentity(normalizedTasks);
        validateDependencies(normalizedTasks);
        return new TaskPlan(List.copyOf(normalizedTasks));
    }

    /**
     * 规范化单个任务的文本、集合和业务字段，并重新计算缺失字段。
     *
     * @param task 模型返回的原始子任务
     * @return 已完成字段规范化的子任务
     */
    private PlannedTask normalizeTask(PlannedTask task) {
        if (task == null) {
            throw invalid("任务计划中不能包含空任务");
        }
        String taskId = requiredText(task.taskId(), "任务标识");
        String originalClause = requiredText(task.originalClause(), "任务原文");
        String standaloneQuestion = requiredText(task.standaloneQuestion(), "独立问题");
        if (task.intent() == null) {
            throw invalid("任务意图不能为空");
        }
        // 业务槽位只保留规范化后的文本和姓名，避免下游重复处理空白和重复项。
        TaskSlots slots = normalizeSlots(task.slots());
        AgentIntent normalizedIntent = normalizeIntent(task.intent(), slots);
        validateRelevantSlots(normalizedIntent, slots);
        List<String> missingFields = calculateMissingFields(normalizedIntent, slots);
        List<String> dependencies = normalizeList(task.dependsOn(), "任务依赖");
        List<String> unresolvedReferences =
                normalizeList(task.unresolvedReferences(), "未解析指代");
        return new PlannedTask(
                taskId,
                task.sequence(),
                normalizedIntent,
                originalClause,
                standaloneQuestion,
                slots,
                missingFields,
                dependencies,
                task.workflowRelation() == null
                        ? WorkflowRelation.INDEPENDENT
                        : task.workflowRelation(),
                unresolvedReferences);
    }

    /**
     * 根据服务端业务规则校正容易混淆的交易意图。
     *
     * @param intent 规划模型返回的原始意图
     * @param slots 已规范化的业务槽位
     * @return 可安全进入固定执行链的意图
     */
    private AgentIntent normalizeIntent(
            AgentIntent intent,
            TaskSlots slots) {
        // 整单取消不允许选择乘车人；指定姓名时必须进入支持部分操作的退票链。
        if (intent == AgentIntent.ORDER_CANCELLATION && !slots.passengerNames().isEmpty()) {
            return AgentIntent.TICKET_REFUND;
        }
        return intent;
    }

    /**
     * 规范化统一业务槽位对象，缺失对象按全空槽位处理。
     *
     * @param slots 模型返回的业务槽位
     * @return 已清理空白和重复姓名的业务槽位
     */
    private TaskSlots normalizeSlots(TaskSlots slots) {
        TaskSlots source = slots == null ? EMPTY_SLOTS : slots;
        return new TaskSlots(
                optionalText(source.departure()),
                optionalText(source.arrival()),
                optionalText(source.departureDate()),
                optionalText(source.trainNumber()),
                optionalText(source.departureTime()),
                optionalText(source.seatClass()),
                source.selectionPolicy(),
                normalizeList(source.passengerNames(), "乘车人姓名"),
                optionalText(source.orderSn()),
                optionalText(source.ridingDate()));
    }

    /**
     * 拒绝与当前意图无关的槽位，防止不同子任务之间发生业务字段串线。
     *
     * @param intent 当前任务意图
     * @param slots 已规范化业务槽位
     */
    private void validateRelevantSlots(AgentIntent intent, TaskSlots slots) {
        boolean valid = switch (intent) {
            case GENERAL_CHAT -> allEmpty(slots);
            case TRAIN_QUERY -> empty(slots.passengerNames())
                    && slots.selectionPolicy() == null
                    && absent(slots.orderSn())
                    && absent(slots.ridingDate());
            case TRAIN_STOP_QUERY -> absent(slots.departure())
                    && absent(slots.arrival())
                    && absent(slots.departureTime())
                    && absent(slots.seatClass())
                    && slots.selectionPolicy() == null
                    && empty(slots.passengerNames())
                    && absent(slots.orderSn())
                    && absent(slots.ridingDate());
            case PASSENGER_QUERY -> absent(slots.departure())
                    && absent(slots.arrival())
                    && absent(slots.departureDate())
                    && absent(slots.trainNumber())
                    && absent(slots.departureTime())
                    && absent(slots.seatClass())
                    && slots.selectionPolicy() == null
                    && absent(slots.orderSn())
                    && absent(slots.ridingDate());
            case ORDER_QUERY -> absent(slots.departure())
                    && absent(slots.arrival())
                    && absent(slots.departureDate())
                    && absent(slots.departureTime())
                    && absent(slots.seatClass())
                    && slots.selectionPolicy() == null;
            case PAYMENT_QUERY -> absent(slots.departure())
                    && absent(slots.arrival())
                    && absent(slots.departureDate())
                    && absent(slots.departureTime())
                    && absent(slots.seatClass())
                    && slots.selectionPolicy() == null;
            case TICKET_PURCHASE -> absent(slots.orderSn()) && absent(slots.ridingDate());
            case ORDER_CANCELLATION, TICKET_REFUND -> absent(slots.departure())
                    && absent(slots.arrival())
                    && absent(slots.departureDate())
                    && absent(slots.departureTime())
                    && absent(slots.seatClass())
                    && slots.selectionPolicy() == null;
        };
        if (!valid) {
            throw invalid("任务包含与意图无关的业务字段: " + intent.name());
        }
    }

    /**
     * 根据确定性业务规则重新计算任务执行前必须补充的字段。
     *
     * @param intent 当前任务意图
     * @param slots 已规范化业务槽位
     * @return 不依赖模型判断的缺失字段列表
     */
    private List<String> calculateMissingFields(AgentIntent intent, TaskSlots slots) {
        List<String> missingFields = new ArrayList<>();
        if (intent == AgentIntent.TRAIN_QUERY || intent == AgentIntent.TICKET_PURCHASE) {
            addMissing(missingFields, slots.departure(), "departure");
            addMissing(missingFields, slots.arrival(), "arrival");
            addMissing(missingFields, slots.departureDate(), "departureDate");
        }
        if (intent == AgentIntent.TRAIN_STOP_QUERY) {
            addMissing(missingFields, slots.trainNumber(), "trainNumber");
        }
        if (intent == AgentIntent.PAYMENT_QUERY) {
            addMissing(missingFields, slots.orderSn(), "orderSn");
        }
        if (intent == AgentIntent.TICKET_PURCHASE) {
            addMissing(missingFields, slots.seatClass(), "seatClass");
            if (slots.passengerNames().isEmpty()) {
                missingFields.add("passengerNames");
            }
        }
        return List.copyOf(missingFields);
    }

    /**
     * 校验任务标识、顺序的唯一性以及顺序是否连续。
     *
     * @param tasks 已按顺序排序的任务集合
     */
    private void validateTaskIdentity(List<PlannedTask> tasks) {
        Set<String> taskIds = new HashSet<>();
        for (int index = 0; index < tasks.size(); index++) {
            PlannedTask task = tasks.get(index);
            if (!taskIds.add(task.taskId())) {
                throw invalid("任务标识不能重复: " + task.taskId());
            }
            if (task.sequence() != index + 1) {
                throw invalid("任务顺序必须从 1 开始且连续");
            }
        }
    }

    /**
     * 限制单轮只包含一个需要改变业务状态的交易任务。
     *
     * @param tasks 已规范化任务集合
     */
    private void validateTransactionCount(List<PlannedTask> tasks) {
        long transactionCount = tasks.stream()
                .filter(task -> TRANSACTION_INTENTS.contains(task.intent()))
                .count();
        if (transactionCount > 1) {
            throw invalid("单轮最多只能包含一个交易任务");
        }
    }

    /**
     * 校验依赖目标存在且依赖图无环，防止后续调度永久等待。
     *
     * @param tasks 已规范化任务集合
     */
    private void validateDependencies(List<PlannedTask> tasks) {
        Map<String, PlannedTask> taskById = new HashMap<>();
        tasks.forEach(task -> taskById.put(task.taskId(), task));
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        tasks.forEach(task -> indegree.put(task.taskId(), 0));

        // 将 dependsOn 转换为正向邻接表，供拓扑排序识别循环依赖。
        for (PlannedTask task : tasks) {
            validateSelectionDependency(task, taskById);
            for (String dependency : task.dependsOn()) {
                if (!taskById.containsKey(dependency)) {
                    throw invalid("任务依赖不存在: " + dependency);
                }
                if (task.taskId().equals(dependency)) {
                    throw invalid("任务不能依赖自身: " + task.taskId());
                }
                if (TRANSACTION_INTENTS.contains(taskById.get(dependency).intent())) {
                    // 交易任务必须是本轮终点，后续查询不能依赖尚待用户确认的写操作结果。
                    throw invalid("交易任务不能成为其他任务的依赖: " + dependency);
                }
                indegree.compute(task.taskId(), (ignored, value) -> value + 1);
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(task.taskId());
            }
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((taskId, degree) -> {
            if (degree == 0) {
                ready.add(taskId);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String taskId = ready.removeFirst();
            visited++;
            for (String dependent : dependents.getOrDefault(taskId, List.of())) {
                int nextDegree = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (nextDegree == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        if (visited != tasks.size()) {
            throw invalid("任务依赖不能形成循环");
        }
    }

    /**
     * 校验确定性选车策略只依赖查票结果，且不与用户指定车次或时间冲突。
     *
     * @param task 当前已规范化任务
     * @param taskById 当前计划任务索引
     */
    private void validateSelectionDependency(
            PlannedTask task,
            Map<String, PlannedTask> taskById) {
        TrainSelectionPolicy policy = task.slots().selectionPolicy();
        if (policy == null) {
            return;
        }
        if (task.intent() != AgentIntent.TICKET_PURCHASE) {
            throw invalid("选车策略只能用于购票任务");
        }
        if (!absent(task.slots().trainNumber()) || !absent(task.slots().departureTime())) {
            throw invalid("选车策略不能与明确车次或出发时间同时使用");
        }

        // 策略只能消费本轮真实查票结果，不能由规划模型凭空选车。
        boolean dependsOnTrainQuery = task.dependsOn().stream()
                .map(taskById::get)
                .anyMatch(dependency -> dependency != null
                        && dependency.intent() == AgentIntent.TRAIN_QUERY);
        if (!dependsOnTrainQuery) {
            throw invalid("使用选车策略的购票任务必须依赖查票任务");
        }
    }

    /**
     * 在字段为空时追加稳定的缺失字段名称。
     *
     * @param missingFields 缺失字段累积容器
     * @param value 待检查字段值
     * @param fieldName 对外稳定字段名称
     */
    private void addMissing(List<String> missingFields, String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            missingFields.add(fieldName);
        }
    }

    /**
     * 规范化模型返回的短文本集合，并拒绝异常规模。
     *
     * @param values 原始文本集合
     * @param fieldName 用于错误提示的字段名称
     * @return 去除空白、空值和重复项后的不可变集合
     */
    private List<String> normalizeList(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_LIST_ITEMS) {
            throw invalid(fieldName + "数量不能超过 " + MAX_LIST_ITEMS);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                normalized.add(requiredText(value, fieldName));
            }
        }
        return List.copyOf(normalized);
    }

    /**
     * 校验必填文本并清理首尾空白。
     *
     * @param value 原始文本
     * @param fieldName 用于错误提示的字段名称
     * @return 非空且未超过长度限制的文本
     */
    private String requiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw invalid(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw invalid(fieldName + "长度不能超过 " + MAX_TEXT_LENGTH);
        }
        return normalized;
    }

    /**
     * 清理允许为空的模型文本。
     *
     * @param value 原始文本
     * @return 空值或清理首尾空白后的文本
     */
    private String optionalText(String value) {
        return StringUtils.hasText(value) ? requiredText(value, "业务字段") : null;
    }

    /**
     * 判断统一槽位对象是否完全为空。
     *
     * @param slots 已规范化槽位
     * @return 所有文本和集合字段是否为空
     */
    private boolean allEmpty(TaskSlots slots) {
        return absent(slots.departure())
                && absent(slots.arrival())
                && absent(slots.departureDate())
                && absent(slots.trainNumber())
                && absent(slots.departureTime())
                && absent(slots.seatClass())
                && slots.selectionPolicy() == null
                && empty(slots.passengerNames())
                && absent(slots.orderSn())
                && absent(slots.ridingDate());
    }

    /**
     * 判断文本是否为空。
     *
     * @param value 待检查文本
     * @return 文本是否没有有效内容
     */
    private boolean absent(String value) {
        return !StringUtils.hasText(value);
    }

    /**
     * 判断集合是否为空。
     *
     * @param values 待检查集合
     * @return 集合是否为空
     */
    private boolean empty(List<String> values) {
        return values == null || values.isEmpty();
    }

    /**
     * 创建可触发结构化模型候选降级的校验异常。
     *
     * @param message 稳定校验错误
     * @return 非法模型输出异常
     */
    private InvalidModelOutputException invalid(String message) {
        return new InvalidModelOutputException(message);
    }
}
