package org.opengoofy.index12306.ai.agentservice.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.repository.AgentWorkflowRepository;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PurchaseInputCollectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.PassengerResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证购票工作流中的乘车人精确匹配、表单选择和草案边界校验。
 */
@ActiveProfiles("test")
@SpringBootTest
class PurchaseWorkflowServiceTests {

    @Autowired
    private PurchaseWorkflowService purchaseWorkflowService;

    @Autowired
    private AgentWorkflowRepository workflowRepository;

    /**
     * 清理当前测试创建的工作流记录。
     */
    @AfterEach
    void cleanUp() {
        // 测试数据没有外键依赖，可按仓储边界直接清理。
        workflowRepository.delete(null);
    }

    /**
     * 验证唯一姓名能够自动匹配，并且草案只能使用服务端确认的行程和乘车人。
     */
    @Test
    void resolvesUniqueNameAndValidatesDraftContext() {
        AgentRequestContext requestContext = requestContext();
        List<PassengerOption> options = List.of(
                new PassengerOption("passenger-1", "万重山", "31***********1234", 0, 1),
                new PassengerOption("passenger-2", "李明", "32***********5678", 0, 1));

        // 姓名唯一命中后，服务端直接保存乘车人标识并推进到草案创建阶段。
        PassengerResolutionResult result = purchaseWorkflowService.resolvePassengers(
                requestContext,
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-22",
                List.of("万重山"),
                PurchaseSeatClass.SECOND_CLASS,
                options);

        assertThat(result.status()).isEqualTo(PassengerResolutionStatus.RESOLVED);
        assertThat(result.resolvedPassengers()).extracting("passengerId")
                .containsExactly("passenger-1");
        assertThat(Optional.ofNullable(workflowRepository.selectById(result.workflowId())).orElseThrow().getStage())
                .isEqualTo(WorkflowStage.CREATING_DRAFT);
        assertThat(purchaseWorkflowService.findReadyDraftContext(
                requestContext.userId(), requestContext.conversationId()))
                .get()
                .extracting("selectedPassengerIds", "seatType")
                .containsExactly(
                        List.of("passenger-1"),
                        PurchaseSeatClass.SECOND_CLASS.code());

        // 完全一致的草案允许继续，替换乘车人或行程必须在服务端被拒绝。
        purchaseWorkflowService.validateDraft(
                requestContext.userId(), requestContext.conversationId(),
                "train-1", "北京南", "上海虹桥", "2026-07-22", List.of("passenger-1"));
        assertThatThrownBy(() -> purchaseWorkflowService.validateDraft(
                requestContext.userId(), requestContext.conversationId(),
                "train-1", "北京南", "上海虹桥", "2026-07-22", List.of("passenger-2")))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * 验证未提供姓名时返回选择表单，并在用户勾选后持久化结果和推进阶段。
     */
    @Test
    void requestsSelectionAndPersistsSubmittedPassenger() {
        AgentRequestContext requestContext = requestContext();
        List<PassengerOption> options = List.of(
                new PassengerOption("passenger-1", "万重山", "31***********1234", 0, 1),
                new PassengerOption("passenger-2", "李明", "32***********5678", 0, 1));

        // 没有姓名时不猜测默认乘车人，而是返回当前账号的安全候选列表。
        PassengerResolutionResult result = purchaseWorkflowService.resolvePassengers(
                requestContext,
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-22",
                List.of(),
                PurchaseSeatClass.SECOND_CLASS,
                options);

        assertThat(result.status()).isEqualTo(PassengerResolutionStatus.SELECTION_REQUIRED);
        assertThat(purchaseWorkflowService.findPendingSelection(
                requestContext.userId(), requestContext.conversationId())).isPresent();

        // 用户提交的标识必须来自候选列表，合法选择推进到草案创建阶段。
        PassengerSelectionResult selection = purchaseWorkflowService.selectPassengers(
                requestContext.userId(),
                result.workflowId(),
                new PassengerSelectionRequest(List.of("passenger-2")));
        assertThat(selection.stage()).isEqualTo(WorkflowStage.CREATING_DRAFT);
        assertThat(selection.selectedPassengers()).extracting("realName").containsExactly("李明");
        assertThat(purchaseWorkflowService.findPendingSelection(
                requestContext.userId(), requestContext.conversationId())).isEmpty();
    }

    /**
     * 验证姓名规范化仍保持精确身份匹配，不会因全角空格产生假阴性。
     */
    @Test
    void normalizesPassengerNameBeforeExactMatch() {
        AgentRequestContext requestContext = requestContext();
        List<PassengerOption> options = List.of(
                new PassengerOption("passenger-1", "万重山", "31***********1234", 0, 1));

        // 用户输入包含全角空格，规范化后仍应唯一命中同名已核验乘车人。
        PassengerResolutionResult result = purchaseWorkflowService.resolvePassengers(
                requestContext,
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-22",
                List.of("万　重山"),
                PurchaseSeatClass.SECOND_CLASS,
                options);

        assertThat(result.status()).isEqualTo(PassengerResolutionStatus.RESOLVED);
        assertThat(result.resolvedPassengers()).extracting("passengerId")
                .containsExactly("passenger-1");
    }

    /**
     * 验证历史乘车人的核验状态为 0 时仍能按姓名进入既有购票流程。
     */
    @Test
    void acceptsLegacyPassengerWithZeroVerifyStatus() {
        AgentRequestContext requestContext = requestContext();
        List<PassengerOption> options = List.of(
                new PassengerOption("passenger-1", "万重山", "31***********1234", 0, 0));

        // 当前种子数据使用 verifyStatus=0，Agent 不得在查询阶段把真实乘车人过滤掉。
        PassengerResolutionResult result = purchaseWorkflowService.resolvePassengers(
                requestContext,
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-22",
                List.of("万重山"),
                PurchaseSeatClass.SECOND_CLASS,
                options);

        assertThat(result.status()).isEqualTo(PassengerResolutionStatus.RESOLVED);
        assertThat(result.resolvedPassengers()).extracting("passengerId")
                .containsExactly("passenger-1");
    }

    /**
     * 验证不完整购票表达先保存到工作流，后续补充字段时保留已确认的行程信息。
     */
    @Test
    void collectsPurchaseInputAcrossMessages() {
        AgentRequestContext requestContext = requestContext();

        // 第一条只给出区间，工作流必须立即存在并明确指出尚缺日期、席别和乘车人。
        PurchaseInputCollectionResult first = purchaseWorkflowService.collectInput(
                requestContext, "北京", "上海", null, null, List.of());
        assertThat(first.stage()).isEqualTo(WorkflowStage.COLLECTING_TRIP);
        assertThat(first.missingFields()).containsExactly("乘车日期", "席别", "乘车人");

        // 后续补充不会要求重发出发和到达站，信息齐全后进入车次选择阶段而非直接创建草案。
        PurchaseInputCollectionResult second = purchaseWorkflowService.collectInput(
                requestContext, null, null, "2026-08-08", PurchaseSeatClass.SECOND_CLASS, List.of("万重山"));
        assertThat(second.workflowId()).isEqualTo(first.workflowId());
        assertThat(second.stage()).isEqualTo(WorkflowStage.SELECTING_TRAIN);
        assertThat(second.readyForTrainQuery()).isTrue();
        assertThat(second.context())
                .extracting("departure", "arrival", "departureDate", "seatType", "requestedPassengerNames")
                .containsExactly("北京", "上海", "2026-08-08", PurchaseSeatClass.SECOND_CLASS.code(), List.of("万重山"));
    }

    /**
     * 验证用户修改已收集的行程字段会废弃旧选车状态并创建新的同类型活动工作流。
     */
    @Test
    void replacesSelectionStateWhenTripFieldChanges() {
        AgentRequestContext requestContext = requestContext();
        PurchaseInputCollectionResult first = purchaseWorkflowService.collectInput(
                requestContext, "北京", "上海", "2026-08-08", PurchaseSeatClass.SECOND_CLASS, List.of("万重山"));

        // 修改日期会使旧车次候选失效，因此返回新的活动工作流而不是复用旧选择状态。
        PurchaseInputCollectionResult changed = purchaseWorkflowService.collectInput(
                requestContext, null, null, "2026-08-09", null, List.of());
        assertThat(changed.workflowId()).isNotEqualTo(first.workflowId());
        assertThat(changed.stage()).isEqualTo(WorkflowStage.SELECTING_TRAIN);
        assertThat(changed.context().departureDate()).isEqualTo("2026-08-09");
        assertThat(Optional.ofNullable(workflowRepository.selectById(first.workflowId())).orElseThrow().getStage())
                .isEqualTo(WorkflowStage.EXPIRED);
    }

    /**
     * 创建相互隔离的测试请求上下文。
     *
     * @return 带唯一用户、会话和轮次标识的请求上下文
     */
    private AgentRequestContext requestContext() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        // 每个测试使用独立业务标识，避免共享 H2 数据影响恢复逻辑。
        return new AgentRequestContext(
                "request-" + suffix,
                "user-" + suffix,
                "tester",
                "conversation-" + suffix.substring(0, 16),
                "turn-" + suffix);
    }
}
