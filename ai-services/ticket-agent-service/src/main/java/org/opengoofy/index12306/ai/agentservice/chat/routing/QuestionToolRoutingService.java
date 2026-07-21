package org.opengoofy.index12306.ai.agentservice.chat.routing;

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
            "resolve_station", "query_tickets", "list_my_passengers", "prepare_ticket_purchase");
    private static final Set<String> CANCELLATION_TOOLS = Set.of(
            "list_my_orders", "get_my_order_detail",
            "preview_order_cancellation", "prepare_order_cancellation");
    private static final Set<String> REFUND_TOOLS = Set.of(
            "list_my_orders", "get_my_order_detail",
            "preview_ticket_refund", "prepare_ticket_refund");
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
            "取消(?:订单|购票)|撤销订单");
    private static final Pattern REFUND_PATTERN = Pattern.compile(
            "退票|退款|退掉|退订");
    private static final Pattern TRAIN_CODE_PATTERN = Pattern.compile(
            "(?i)(?:G|D|C|Z|T|K)\\d{1,5}");

    /**
     * 使用本地规则判断回答模型是否需要获得 MCP 工具。
     *
     * @param effectiveQuestion 问题改写后供回答模型使用的独立问题
     * @return 本轮问答路径和允许注册的最小工具集合
     */
    public QuestionRoutingDecision route(String effectiveQuestion) {
        if (!StringUtils.hasText(effectiveQuestion)) {
            // 空问题不具备任何可执行业务意图，交由普通回答路径处理输入错误。
            return QuestionRoutingDecision.chatOnly();
        }

        Set<String> allowedToolNames = new LinkedHashSet<>();

        // 先识别相互独立的只读查询能力，同一问题命中多类业务时自动合并工具组。
        if (matches(TRAIN_QUERY_PATTERN, effectiveQuestion)
                || matches(TRAIN_CODE_PATTERN, effectiveQuestion)) {
            allowedToolNames.addAll(TRAIN_QUERY_TOOLS);
        }
        if (matches(TRAIN_STOP_PATTERN, effectiveQuestion)) {
            allowedToolNames.addAll(TRAIN_STOP_TOOLS);
        }
        if (matches(PASSENGER_PATTERN, effectiveQuestion)) {
            allowedToolNames.addAll(PASSENGER_TOOLS);
        }
        if (matches(ORDER_PATTERN, effectiveQuestion)) {
            allowedToolNames.addAll(ORDER_QUERY_TOOLS);
        }
        if (matches(PAYMENT_PATTERN, effectiveQuestion)) {
            allowedToolNames.addAll(ORDER_QUERY_TOOLS);
            allowedToolNames.addAll(PAYMENT_TOOLS);
        }

        // 写操作只向模型开放草案工具，确认后的真实写工具继续由服务端按钮接口隔离执行。
        if (matches(PURCHASE_PATTERN, effectiveQuestion)) {
            allowedToolNames.addAll(PURCHASE_TOOLS);
        }
        if (matches(CANCELLATION_PATTERN, effectiveQuestion)) {
            allowedToolNames.addAll(CANCELLATION_TOOLS);
        }
        if (matches(REFUND_PATTERN, effectiveQuestion)) {
            allowedToolNames.addAll(REFUND_TOOLS);
        }

        return allowedToolNames.isEmpty()
                ? QuestionRoutingDecision.chatOnly()
                : QuestionRoutingDecision.toolAssisted(allowedToolNames);
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
     * 问题分流结果。
     *
     * @param route 普通问答或工具辅助路径
     * @param allowedToolNames 本轮允许注册到回答模型的工具名称
     */
    public record QuestionRoutingDecision(
            QuestionRoute route,
            Set<String> allowedToolNames) {

        /**
         * 创建不携带任何工具的普通问答结果。
         *
         * @return 普通问答分流结果
         */
        public static QuestionRoutingDecision chatOnly() {
            // 普通问答固定使用不可变空集合，避免后续阶段意外追加工具。
            return new QuestionRoutingDecision(QuestionRoute.CHAT_ONLY, Set.of());
        }

        /**
         * 创建仅携带已选工具组的业务问答结果。
         *
         * @param allowedToolNames 本轮命中的工具名称
         * @return 工具辅助分流结果
         */
        public static QuestionRoutingDecision toolAssisted(Set<String> allowedToolNames) {
            // 复制规则计算结果，防止调用方修改集合破坏本轮安全边界。
            return new QuestionRoutingDecision(
                    QuestionRoute.TOOL_ASSISTED, Set.copyOf(allowedToolNames));
        }
    }
}
