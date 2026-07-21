package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationTurnContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 根据独立问题选择普通问答或业务工具链路。
 */
@Service
public class QuestionToolRoutingService {

    private static final Set<String> TRAIN_QUERY_TOOLS = Set.of(
            "resolve_station", "query_tickets");
    private static final Set<String> TRAIN_STOP_TOOLS = Set.of(
            "query_train_stops");
    private static final Set<String> PASSENGER_TOOLS = Set.of(
            "list_my_passengers");
    private static final Set<String> ORDER_QUERY_TOOLS = Set.of(
            "list_my_orders", "get_my_order_detail");
    private static final Set<String> PAYMENT_TOOLS = Set.of(
            "query_pay_status");
    private static final Set<String> PURCHASE_TOOLS = Set.of(
            "resolve_station", "query_tickets", "resolve_purchase_passengers", "prepare_ticket_purchase");
    private static final Set<String> CANCELLATION_TOOLS = Set.of(
            "resolve_order_cancellation", "prepare_order_cancellation");
    private static final Set<String> REFUND_TOOLS = Set.of(
            "resolve_ticket_refund", "prepare_ticket_refund");
    private static final Pattern TRAIN_QUERY_PATTERN = Pattern.compile(
            "余票|票价|车次|列车|火车|高铁|动车|席别|座位|一等座|二等座|商务座|"
                    + "硬座|软座|硬卧|软卧|无座|站点|车站|"
                    + "(?:查|查询|看看|有没有|还有).{0,20}票|票.{0,10}(?:还有|查询|价格|多少钱)");
    private static final Pattern TRAIN_STOP_PATTERN = Pattern.compile(
            "经停|停靠|途经|沿途|到哪些站");
    private static final Pattern PASSENGER_PATTERN = Pattern.compile(
            "乘车人|旅客|联系人");
    private static final Pattern ORDER_PATTERN = Pattern.compile(
            "订单|订单号");
    private static final Pattern PAYMENT_PATTERN = Pattern.compile(
            "支付|付款|待支付|已支付");
    private static final Pattern PURCHASE_PATTERN = Pattern.compile(
            "购票|买票|订票|预订|下单|购买.{0,30}(?:票|车次)");
    private static final Pattern CANCELLATION_PATTERN = Pattern.compile(
            "取消.{0,20}(?:订单|购票)|撤销订单");
    private static final Pattern REFUND_PATTERN = Pattern.compile(
            "退票|退款|退掉|退订");
    private static final Pattern TRAIN_CODE_PATTERN = Pattern.compile(
            "(?i)(?:G|D|C|Z|T|K)\\d{1,5}");
    private static final Pattern CONTEXTUAL_TRAIN_PATTERN = Pattern.compile(
            "第[一二三四五六七八九十\\d]+个|这个|那个|这趟|那趟|上一个|下一个|还有吗|怎么样|呢[？?]?$");
    private static final Pattern CONTEXTUAL_PURCHASE_PATTERN = Pattern.compile(
            "刚才.{0,10}(?:那个|那趟).{0,10}(?:要|买)|(?:这个|那个|这趟|那趟).{0,10}(?:要了|买了)|"
                    + "就(?:要|买)(?:这个|那个)|帮我弄一张");
    private static final Pattern CONTEXTUAL_ORDER_PATTERN = Pattern.compile(
            "(?:那个|这笔|刚才).{0,10}(?:怎么样|什么状态|支付了吗|成功了吗)|看看我的那个");

    /**
     * 使用本地规则判断回答模型是否需要获得 MCP 工具。
     *
     * @param effectiveQuestion 问题改写后供回答模型使用的独立问题
     * @return 本轮问答路径和允许注册的最小工具集合
     */
    public QuestionRoutingDecision route(String effectiveQuestion) {
        // 无历史的调用只执行当前问题的明确业务规则，主要供独立问题和单元测试使用。
        return route(effectiveQuestion, null);
    }

    /**
     * 结合当前独立问题和最近业务上下文选择最小 MCP 工具集合。
     *
     * @param effectiveQuestion 问题改写后供回答模型使用的独立问题
     * @param history 当前会话摘要、结构化状态和最近完整轮次
     * @return 本轮问答路径、命中业务组和允许工具集合
     */
    public QuestionRoutingDecision route(
            String effectiveQuestion,
            ConversationHistoryContext history) {
        if (!StringUtils.hasText(effectiveQuestion)) {
            // 空问题不具备任何可执行业务意图，交由普通回答路径处理输入错误。
            return QuestionRoutingDecision.chatOnly();
        }

        Set<String> allowedToolNames = new LinkedHashSet<>();
        Set<BusinessGroup> matchedGroups = new LinkedHashSet<>();

        // 先识别相互独立的只读查询能力，同一问题命中多类业务时自动合并工具组。
        if (matches(TRAIN_QUERY_PATTERN, effectiveQuestion)
                || matches(TRAIN_CODE_PATTERN, effectiveQuestion)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.TRAIN_QUERY, TRAIN_QUERY_TOOLS);
        }
        if (matches(TRAIN_STOP_PATTERN, effectiveQuestion)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.TRAIN_STOP, TRAIN_STOP_TOOLS);
        }
        if (matches(PASSENGER_PATTERN, effectiveQuestion)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.PASSENGER, PASSENGER_TOOLS);
        }
        if (matches(ORDER_PATTERN, effectiveQuestion)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.ORDER_QUERY, ORDER_QUERY_TOOLS);
        }
        if (matches(PAYMENT_PATTERN, effectiveQuestion)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.ORDER_QUERY, ORDER_QUERY_TOOLS);
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.PAYMENT, PAYMENT_TOOLS);
        }

        // 写操作只向模型开放草案工具，确认后的真实写工具继续由服务端按钮接口隔离执行。
        if (matches(PURCHASE_PATTERN, effectiveQuestion)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.PURCHASE, PURCHASE_TOOLS);
        }
        if (matches(CANCELLATION_PATTERN, effectiveQuestion)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.CANCELLATION, CANCELLATION_TOOLS);
            // 取消链路由服务端包装器内部查询本人订单，回答模型不再直接获得订单列表后猜测目标。
            matchedGroups.remove(BusinessGroup.ORDER_QUERY);
            allowedToolNames.removeAll(ORDER_QUERY_TOOLS);
            matchedGroups.remove(BusinessGroup.PAYMENT);
            allowedToolNames.removeAll(PAYMENT_TOOLS);
            matchedGroups.remove(BusinessGroup.TRAIN_QUERY);
            allowedToolNames.removeAll(TRAIN_QUERY_TOOLS);
        }
        if (matches(REFUND_PATTERN, effectiveQuestion)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.REFUND, REFUND_TOOLS);
            // 退票解析器在服务端内部读取本人订单和可退预览，模型不直接接触列表后猜测退票范围。
            matchedGroups.remove(BusinessGroup.ORDER_QUERY);
            allowedToolNames.removeAll(ORDER_QUERY_TOOLS);
            matchedGroups.remove(BusinessGroup.PAYMENT);
            allowedToolNames.removeAll(PAYMENT_TOOLS);
            matchedGroups.remove(BusinessGroup.TRAIN_QUERY);
            allowedToolNames.removeAll(TRAIN_QUERY_TOOLS);
        }

        // 明确规则未覆盖口语化追问时，只使用最近业务事实补足工具组，不重新调用意图模型。
        applyContextFallback(effectiveQuestion, history, matchedGroups, allowedToolNames);

        return allowedToolNames.isEmpty()
                ? QuestionRoutingDecision.chatOnly()
                : QuestionRoutingDecision.toolAssisted(
                        resolveIntent(matchedGroups), matchedGroups, allowedToolNames);
    }

    /**
     * 按写操作优先于只读查询的规则，将命中的业务组归并为一个稳定主意图。
     *
     * @param matchedGroups 当前问题命中的业务组
     * @return 可供后续工作流选择使用的主意图
     */
    private AgentIntent resolveIntent(Set<BusinessGroup> matchedGroups) {
        // 高风险写操作必须优先分流，避免同时出现订单或车票关键词时退化为只读查询。
        if (matchedGroups.contains(BusinessGroup.CANCELLATION)) {
            return AgentIntent.ORDER_CANCELLATION;
        }
        if (matchedGroups.contains(BusinessGroup.REFUND)) {
            return AgentIntent.TICKET_REFUND;
        }
        if (matchedGroups.contains(BusinessGroup.PURCHASE)) {
            return AgentIntent.TICKET_PURCHASE;
        }
        if (matchedGroups.contains(BusinessGroup.PAYMENT)) {
            return AgentIntent.PAYMENT_QUERY;
        }
        if (matchedGroups.contains(BusinessGroup.ORDER_QUERY)) {
            return AgentIntent.ORDER_QUERY;
        }
        if (matchedGroups.contains(BusinessGroup.PASSENGER)) {
            return AgentIntent.PASSENGER_QUERY;
        }
        if (matchedGroups.contains(BusinessGroup.TRAIN_STOP)) {
            return AgentIntent.TRAIN_STOP_QUERY;
        }
        return AgentIntent.TRAIN_QUERY;
    }

    /**
     * 对依赖历史的购票、查票和订单追问补充工具组。
     *
     * @param question 当前独立问题或改写失败后保留的原问题
     * @param history 当前会话上下文
     * @param matchedGroups 已命中的业务组
     * @param allowedToolNames 已选工具名称
     */
    private void applyContextFallback(
            String question,
            ConversationHistoryContext history,
            Set<BusinessGroup> matchedGroups,
            Set<String> allowedToolNames) {
        if (history == null || history.recentTurns().isEmpty()) {
            return;
        }

        // 兜底只查看最近一轮和服务端摘要状态，避免较早业务主题污染当前问题。
        String businessContext = recentBusinessContext(history);
        boolean trainContext = matches(TRAIN_QUERY_PATTERN, businessContext)
                || matches(TRAIN_CODE_PATTERN, businessContext);
        boolean orderContext = matches(ORDER_PATTERN, businessContext)
                || matches(PAYMENT_PATTERN, businessContext);
        if (trainContext && matches(CONTEXTUAL_TRAIN_PATTERN, question)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.TRAIN_QUERY, TRAIN_QUERY_TOOLS);
        }
        if (trainContext && matches(CONTEXTUAL_PURCHASE_PATTERN, question)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.PURCHASE, PURCHASE_TOOLS);
        }
        if (orderContext
                && !matchedGroups.contains(BusinessGroup.CANCELLATION)
                && !matchedGroups.contains(BusinessGroup.REFUND)
                && matches(CONTEXTUAL_ORDER_PATTERN, question)) {
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.ORDER_QUERY, ORDER_QUERY_TOOLS);
            addGroup(matchedGroups, allowedToolNames, BusinessGroup.PAYMENT, PAYMENT_TOOLS);
        }
    }

    /**
     * 提取仅供兜底判断使用的最近业务上下文。
     *
     * @param history 当前会话上下文
     * @return 摘要状态和最近完整轮次组成的文本
     */
    private String recentBusinessContext(ConversationHistoryContext history) {
        StringBuilder context = new StringBuilder();
        if (StringUtils.hasText(history.summaryContent())) {
            context.append(history.summaryContent()).append('\n');
        }
        if (StringUtils.hasText(history.structuredState())) {
            context.append(history.structuredState()).append('\n');
        }
        ConversationTurnContext latestTurn = history.recentTurns().get(history.recentTurns().size() - 1);
        // 最近一轮同时保留用户问题和助手回答，覆盖选车、草案和订单状态追问。
        context.append(latestTurn.userMessage().content()).append('\n');
        context.append(latestTurn.assistantMessage().content());
        return context.toString();
    }

    /**
     * 合并命中的业务组及其最小工具集合。
     *
     * @param matchedGroups 已命中的业务组
     * @param allowedToolNames 已选工具名称
     * @param group 当前命中的业务组
     * @param toolNames 当前业务组依赖的工具名称
     */
    private void addGroup(
            Set<BusinessGroup> matchedGroups,
            Set<String> allowedToolNames,
            BusinessGroup group,
            Set<String> toolNames) {
        // 业务组和工具集合分别去重，支持一个问题同时查询订单并检查支付状态。
        matchedGroups.add(group);
        allowedToolNames.addAll(toolNames);
    }

    /**
     * 判断问题是否命中指定业务规则。
     *
     * @param pattern 预编译的业务关键词规则
     * @param question 当前独立问题
     * @return 是否命中该工具组
     */
    private boolean matches(Pattern pattern, String question) {
        // 所有规则统一使用子串匹配，允许自然语言在业务词前后携带修饰信息。
        return pattern.matcher(question).find();
    }

    /**
     * 回答模型可使用的两种执行路径。
     */
    public enum QuestionRoute {
        CHAT_ONLY,
        TOOL_ASSISTED
    }

    /**
     * 可独立组合的票务业务工具组。
     */
    public enum BusinessGroup {
        TRAIN_QUERY,
        TRAIN_STOP,
        PASSENGER,
        ORDER_QUERY,
        PAYMENT,
        PURCHASE,
        CANCELLATION,
        REFUND
    }

    /**
     * 问题分流结果。
     *
     * @param route 普通问答或工具辅助路径
     * @param intent 当前问题的稳定主意图
     * @param matchedGroups 当前问题命中的业务工具组
     * @param allowedToolNames 本轮允许注册到回答模型的工具名称
     */
    public record QuestionRoutingDecision(
            QuestionRoute route,
            AgentIntent intent,
            Set<BusinessGroup> matchedGroups,
            Set<String> allowedToolNames) {

        /**
         * 创建不携带任何工具的普通问答结果。
         *
         * @return 普通问答分流结果
         */
        public static QuestionRoutingDecision chatOnly() {
            // 普通问答固定使用不可变空集合，避免后续阶段意外追加工具。
            return new QuestionRoutingDecision(
                    QuestionRoute.CHAT_ONLY,
                    AgentIntent.GENERAL_CHAT,
                    Set.of(),
                    Set.of());
        }

        /**
         * 创建仅携带已选工具组的业务问答结果。
         *
         * @param intent 当前问题的稳定主意图
         * @param matchedGroups 本轮命中的业务组
         * @param allowedToolNames 本轮命中的工具名称
         * @return 工具辅助分流结果
         */
        public static QuestionRoutingDecision toolAssisted(
                AgentIntent intent,
                Set<BusinessGroup> matchedGroups,
                Set<String> allowedToolNames) {
            // 复制规则计算结果，防止调用方修改业务组或工具集合破坏本轮安全边界。
            return new QuestionRoutingDecision(
                    QuestionRoute.TOOL_ASSISTED,
                    intent,
                    Set.copyOf(matchedGroups),
                    Set.copyOf(allowedToolNames));
        }
    }
}
