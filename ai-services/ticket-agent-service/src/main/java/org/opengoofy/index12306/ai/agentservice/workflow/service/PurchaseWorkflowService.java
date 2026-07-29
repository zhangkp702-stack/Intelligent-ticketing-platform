package org.opengoofy.index12306.ai.agentservice.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.entity.AgentWorkflowEntity;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PurchaseWorkflowContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.ResolvedPassenger;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowPlanningContext;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.PassengerResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行购票工作流中的乘车人匹配、选择提交和持久化上下文恢复。
 */
@Service
public class PurchaseWorkflowService {

    private static final int MAX_PASSENGERS = 5;

    private final AgentWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    /**
     * 创建购票工作流阶段服务。
     *
     * @param workflowService 通用工作流生命周期服务
     * @param objectMapper 工作流上下文 JSON 转换器
     */
    public PurchaseWorkflowService(
            AgentWorkflowService workflowService,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    /**
     * 按用户提供的姓名精确匹配账号乘车人，并在无法唯一确定时生成选择表单。
     *
     * @param requestContext 当前请求上下文
     * @param trainId 余票查询返回的列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param departureDate 乘车日期
     * @param passengerNames 用户提供的姓名，可为空
     * @param seatClass 用户已经明确的席别，可为空
     * @param options 当前账号的脱敏乘车人列表
     * @return 可供模型继续处理的匹配结果
     */
    public PassengerResolutionResult resolvePassengers(
            AgentRequestContext requestContext,
            String trainId,
            String departure,
            String arrival,
            String departureDate,
            List<String> passengerNames,
            PurchaseSeatClass seatClass,
            List<PassengerOption> options) {
        List<String> normalizedNames = normalizeNames(passengerNames);
        // 候选项必须具备稳定业务标识和姓名，异常 MCP 数据不能进入工作流或前端选择表单。
        List<PassengerOption> safeOptions = options == null ? List.of() : options.stream()
                .filter(option -> option != null
                        && StringUtils.hasText(option.passengerId())
                        && StringUtils.hasText(option.realName()))
                .toList();
        if (safeOptions.isEmpty()) {
            return new PassengerResolutionResult(
                    PassengerResolutionStatus.NO_PASSENGERS,
                    null,
                    List.of(),
                    normalizedNames,
                    "当前账号没有可用乘车人，请先在常用信息管理中添加乘车人");
        }

        // 双方使用相同规范化键精确匹配，绝不使用证件号、手机号或相似度猜测身份。
        Map<String, List<PassengerOption>> optionsByName = safeOptions.stream()
                .filter(option -> StringUtils.hasText(option.realName()))
                .collect(Collectors.groupingBy(option -> normalizeName(option.realName())));
        List<PassengerOption> resolved = new ArrayList<>();
        List<String> unmatchedNames = new ArrayList<>();
        for (String name : normalizedNames) {
            List<PassengerOption> matches = optionsByName.getOrDefault(name, List.of());
            if (matches.size() == 1) {
                resolved.add(matches.get(0));
            } else {
                unmatchedNames.add(name);
            }
        }

        boolean selectionRequired = normalizedNames.isEmpty() || !unmatchedNames.isEmpty();
        List<String> selectedIds = selectionRequired
                ? List.of()
                : resolved.stream().map(PassengerOption::passengerId).distinct().toList();
        PurchaseWorkflowContext workflowContext = new PurchaseWorkflowContext(
                requiredText(trainId, "车次标识"),
                requiredText(departure, "出发站"),
                requiredText(arrival, "到达站"),
                requiredText(departureDate, "乘车日期"),
                normalizedNames,
                safeOptions,
                selectedIds,
                seatClass == null ? null : seatClass.code(),
                List.of());
        AgentWorkflowEntity workflow = createOrUpdateWorkflow(requestContext, workflowContext, selectionRequired);

        if (selectionRequired) {
            // 候选项已经写入数据库，完成阶段会从权威工作流重建脱敏选择表单。
            return new PassengerResolutionResult(
                    PassengerResolutionStatus.SELECTION_REQUIRED,
                    workflow.getId(),
                    resolved.stream().map(this::toResolvedPassenger).toList(),
                    List.copyOf(unmatchedNames),
                    "需要用户在乘车人列表中完成选择，不能询问证件号码");
        }

        return new PassengerResolutionResult(
                PassengerResolutionStatus.RESOLVED,
                workflow.getId(),
                resolved.stream().map(this::toResolvedPassenger).toList(),
                List.of(),
                "乘车人已按姓名唯一匹配，可以使用 passengerId 继续生成购票草案");
    }

    /**
     * 提交用户在结构化表单中勾选的乘车人，并推进到席别或草案阶段。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param request 选择请求
     * @return 已选择乘车人和下一阶段
     */
    public PassengerSelectionResult selectPassengers(
            String userId,
            String workflowId,
            PassengerSelectionRequest request) {
        AgentWorkflowEntity workflow = workflowService.findActiveById(userId, workflowId)
                .orElseThrow(() -> new IllegalArgumentException("购票工作流不存在或已经失效"));
        if (workflow.getWorkflowType() != WorkflowType.TICKET_PURCHASE
                || workflow.getStage() != WorkflowStage.SELECTING_PASSENGERS) {
            throw new IllegalStateException("当前购票工作流不处于乘车人选择阶段");
        }
        PurchaseWorkflowContext context = readContext(workflow.getContextJson());
        List<String> selectedIds = normalizeSelectedIds(request);

        // 选择值必须完全来自数据库上下文中的候选集合，不能由浏览器构造任意乘车人标识。
        Map<String, PassengerOption> optionById = context.passengerOptions().stream()
                .collect(Collectors.toMap(PassengerOption::passengerId, Function.identity()));
        if (selectedIds.stream().anyMatch(id -> !optionById.containsKey(id))) {
            throw new SecurityException("选择中包含不属于当前账号的乘车人");
        }
        PurchaseWorkflowContext updatedContext = new PurchaseWorkflowContext(
                context.trainId(),
                context.departure(),
                context.arrival(),
                context.departureDate(),
                context.requestedPassengerNames(),
                context.passengerOptions(),
                selectedIds,
                context.seatType(),
                context.chooseSeats());
        WorkflowStage nextStage = context.seatType() == null
                ? WorkflowStage.SELECTING_SEAT_CLASS
                : WorkflowStage.CREATING_DRAFT;
        AgentWorkflowEntity updated = workflowService.advance(
                userId,
                workflowId,
                WorkflowStage.SELECTING_PASSENGERS,
                nextStage,
                writeContext(updatedContext));
        List<ResolvedPassenger> selected = selectedIds.stream()
                .map(optionById::get)
                .map(this::toResolvedPassenger)
                .toList();
        return new PassengerSelectionResult(updated.getId(), updated.getStage(), selected);
    }

    /**
     * 恢复会话中等待选择的乘车人表单。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @return 可恢复选择视图
     */
    public Optional<PassengerSelectionView> findPendingSelection(String userId, String conversationId) {
        // 页面刷新只恢复数据库中仍处于 SELECTING_PASSENGERS 的购票工作流。
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_PURCHASE)
                .filter(workflow -> workflow.getStage() == WorkflowStage.SELECTING_PASSENGERS)
                .map(workflow -> toSelectionView(workflow, readContext(workflow.getContextJson()), List.of()));
    }

    /**
     * 读取已经完成行程、乘车人和席别校验且可以创建草案的购票上下文。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @return 可安全创建待确认草案的服务端上下文
     */
    public Optional<PurchaseWorkflowContext> findReadyDraftContext(String userId, String conversationId) {
        // 只有服务端明确推进到 CREATING_DRAFT 的购票工作流才允许进入确定性草案兜底。
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_PURCHASE)
                .filter(workflow -> workflow.getStage() == WorkflowStage.CREATING_DRAFT)
                .map(workflow -> readContext(workflow.getContextJson()))
                .filter(context -> StringUtils.hasText(context.trainId())
                        && StringUtils.hasText(context.departure())
                        && StringUtils.hasText(context.arrival())
                        && StringUtils.hasText(context.departureDate())
                        && context.selectedPassengerIds() != null
                        && !context.selectedPassengerIds().isEmpty()
                        && context.seatType() != null);
    }

    /**
     * 生成供任务规划模型理解指代的最小购票工作流上下文。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @return 不含候选列表、内部标识和执行指令的活动工作流上下文
     */
    public Optional<WorkflowPlanningContext> activeWorkflowContext(
            String userId,
            String conversationId) {
        // 规划模型只需要工作流阶段和可用于解析指代的业务事实，不需要乘车人内部标识。
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_PURCHASE)
                .map(workflow -> {
                    PurchaseWorkflowContext context = readContext(workflow.getContextJson());
                    Map<String, Object> facts = new java.util.LinkedHashMap<>();
                    putText(facts, "departure", context.departure());
                    putText(facts, "arrival", context.arrival());
                    putText(facts, "departureDate", context.departureDate());
                    if (context.requestedPassengerNames() != null
                            && !context.requestedPassengerNames().isEmpty()) {
                        facts.put("requestedPassengerNames", List.copyOf(context.requestedPassengerNames()));
                    }
                    if (context.seatType() != null) {
                        facts.put("seatClass", PurchaseSeatClass.fromCode(context.seatType()).label());
                    }
                    facts.put("trainSelected", StringUtils.hasText(context.trainId()));
                    facts.put(
                            "selectedPassengerCount",
                            context.selectedPassengerIds() == null ? 0 : context.selectedPassengerIds().size());
                    return new WorkflowPlanningContext(
                            workflow.getWorkflowType(),
                            workflow.getStage(),
                            facts,
                            workflow.getStage() == WorkflowStage.SELECTING_PASSENGERS);
                });
    }

    /**
     * 将非空文本加入规划模型可见的事实白名单。
     *
     * @param facts 规划事实映射
     * @param key 稳定字段名
     * @param value 待筛选文本
     */
    private void putText(
            Map<String, Object> facts,
            String key,
            String value) {
        if (StringUtils.hasText(value)) {
            // 只保留完成指代解析所需的业务文本，不复制完整持久化上下文。
            facts.put(key, value.trim());
        }
    }

    /**
     * 校验模型提交的购票草案是否严格使用服务端工作流已经确认的行程和乘车人。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param trainId 草案中的列车标识
     * @param departure 草案中的出发站
     * @param arrival 草案中的到达站
     * @param departureDate 草案中的乘车日期
     * @param passengerIds 草案中的乘车人标识
     */
    public void validateDraft(
            String userId,
            String conversationId,
            String trainId,
            String departure,
            String arrival,
            String departureDate,
            List<String> passengerIds) {
        AgentWorkflowEntity workflow = workflowService.findActive(userId, conversationId)
                .filter(candidate -> candidate.getWorkflowType() == WorkflowType.TICKET_PURCHASE)
                .orElseThrow(() -> new IllegalStateException("购票草案缺少有效的服务端工作流"));
        if (workflow.getStage() != WorkflowStage.CREATING_DRAFT) {
            throw new IllegalStateException("当前购票工作流尚未进入草案创建阶段");
        }
        PurchaseWorkflowContext context = readContext(workflow.getContextJson());

        // 行程字段必须与乘车人解析阶段保存的上下文一致，禁止模型在阶段切换后替换车次。
        if (!context.trainId().equals(trainId)
                || !context.departure().equals(departure)
                || !context.arrival().equals(arrival)
                || !context.departureDate().equals(departureDate)) {
            throw new IllegalArgumentException("购票草案行程与当前工作流不一致");
        }

        // 使用集合比较允许模型调整乘车人顺序，但不允许增加、遗漏或伪造乘车人标识。
        Set<String> expectedPassengerIds = Set.copyOf(context.selectedPassengerIds());
        List<String> normalizedPassengerIds = passengerIds == null ? List.of() : passengerIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        Set<String> actualPassengerIds = Set.copyOf(normalizedPassengerIds);
        if (expectedPassengerIds.isEmpty()
                || normalizedPassengerIds.size() != (passengerIds == null ? 0 : passengerIds.size())
                || actualPassengerIds.size() != normalizedPassengerIds.size()
                || !expectedPassengerIds.equals(actualPassengerIds)) {
            throw new SecurityException("购票草案乘车人与服务端已确认结果不一致");
        }
    }

    /**
     * 购票草案创建成功后完成当前会话的购票工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     */
    public void completeAfterDraft(String userId, String conversationId) {
        // 草案表已经持久化成功后再完成工作流，失败调用仍保留原阶段供用户重试。
        workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_PURCHASE)
                .ifPresent(workflow -> workflowService.complete(
                        userId,
                        workflow.getId(),
                        workflow.getStage(),
                        workflow.getContextJson()));
    }

    /**
     * 创建或更新乘车人阶段工作流，并按解析结果推进下一阶段。
     */
    private AgentWorkflowEntity createOrUpdateWorkflow(
            AgentRequestContext requestContext,
            PurchaseWorkflowContext context,
            boolean selectionRequired) {
        String contextJson = writeContext(context);
        AgentWorkflowEntity workflow = workflowService.startOrResume(
                requestContext.userId(),
                requestContext.conversationId(),
                WorkflowType.TICKET_PURCHASE,
                WorkflowStage.SELECTING_PASSENGERS,
                contextJson);
        if (workflow.getStage() != WorkflowStage.SELECTING_PASSENGERS) {
            throw new IllegalStateException("当前购票工作流已经完成乘车人选择");
        }
        if (selectionRequired) {
            return workflowService.updateContext(
                    requestContext.userId(),
                    workflow.getId(),
                    WorkflowStage.SELECTING_PASSENGERS,
                    contextJson);
        }
        WorkflowStage nextStage = context.seatType() == null
                ? WorkflowStage.SELECTING_SEAT_CLASS
                : WorkflowStage.CREATING_DRAFT;
        return workflowService.advance(
                requestContext.userId(),
                workflow.getId(),
                WorkflowStage.SELECTING_PASSENGERS,
                nextStage,
                contextJson);
    }

    /**
     * 根据当前上下文构造前端乘车人勾选视图。
     */
    private PassengerSelectionView toSelectionView(
            AgentWorkflowEntity workflow,
            PurchaseWorkflowContext context,
            List<String> unmatchedNames) {
        String prompt = unmatchedNames.isEmpty()
                ? "请选择本次乘车人"
                : "以下姓名未能唯一匹配：" + String.join("、", unmatchedNames) + "。请选择对应乘车人";
        return new PassengerSelectionView(
                workflow.getId(),
                workflow.getStage(),
                prompt,
                context.passengerOptions(),
                1,
                Math.min(MAX_PASSENGERS, context.passengerOptions().size()));
    }

    /**
     * 规范化用户提交的乘车人标识并校验数量和重复项。
     */
    private List<String> normalizeSelectedIds(PassengerSelectionRequest request) {
        if (request == null || request.passengerIds() == null) {
            throw new IllegalArgumentException("请选择乘车人");
        }
        List<String> selectedIds = request.passengerIds().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        Set<String> unique = new HashSet<>(selectedIds);
        if (selectedIds.isEmpty() || selectedIds.size() > MAX_PASSENGERS || unique.size() != selectedIds.size()) {
            throw new IllegalArgumentException("乘车人数量必须在 1 到 5 之间且不能重复");
        }
        return selectedIds;
    }

    /**
     * 规范化模型抽取的姓名列表。
     */
    private List<String> normalizeNames(List<String> passengerNames) {
        if (passengerNames == null) {
            return List.of();
        }
        // 姓名规范化后再去重，避免全角字符或不可见空白制造重复乘车人。
        return passengerNames.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeName)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(MAX_PASSENGERS)
                .toList();
    }

    /**
     * 使用 Unicode NFKC 规范化姓名并移除所有空白字符。
     *
     * @param name 原始姓名
     * @return 用于精确匹配的稳定姓名键
     */
    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        // 兼容全角字符、全角空格和不可见空白，同时保留姓名中的其他可见字符。
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    /**
     * 将候选项转换为不包含证件信息的模型结果。
     */
    private ResolvedPassenger toResolvedPassenger(PassengerOption option) {
        return new ResolvedPassenger(option.passengerId(), option.realName());
    }

    /**
     * 序列化工作流上下文。
     */
    private String writeContext(PurchaseWorkflowContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存购票工作流上下文", exception);
        }
    }

    /**
     * 反序列化工作流上下文。
     */
    private PurchaseWorkflowContext readContext(String contextJson) {
        try {
            return objectMapper.readValue(contextJson, PurchaseWorkflowContext.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("购票工作流上下文已损坏", exception);
        }
    }

    /**
     * 校验工具提供的行程字段。
     */
    private String requiredText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }
}
