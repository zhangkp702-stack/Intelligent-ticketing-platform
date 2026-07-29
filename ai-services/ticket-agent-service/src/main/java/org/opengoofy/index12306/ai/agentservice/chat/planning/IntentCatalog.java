package org.opengoofy.index12306.ai.agentservice.chat.planning;

import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 集中维护任务规划模型可选择的业务意图及其业务边界。
 */
@Component
public class IntentCatalog {

    private final List<IntentDefinition> definitions = List.of(
            new IntentDefinition(
                    AgentIntent.GENERAL_CHAT,
                    "问候、能力介绍，或不属于下列票务业务的普通交流。",
                    List.of("你好", "你能做什么"),
                    List.of("查询订单", "帮我买票")),
            new IntentDefinition(
                    AgentIntent.TRAIN_QUERY,
                    "查询两个站点之间的车次、余票、票价、席别或到发时间。",
                    List.of("明天北京到上海还有票吗", "查一下下午出发的一等座"),
                    List.of("G1 经过哪些站", "购买 G1 次列车")),
            new IntentDefinition(
                    AgentIntent.TRAIN_STOP_QUERY,
                    "已知具体车次时，查询该列车的经停站、各站到发时刻或完整运行路线。",
                    List.of("G1 经过哪些站", "G1 到南京几点"),
                    List.of("北京到上海有哪些车", "G1 还有票吗")),
            new IntentDefinition(
                    AgentIntent.PASSENGER_QUERY,
                    "查询当前账号已经保存的乘车人，或按姓名查找本人账号中的乘车人。",
                    List.of("查询我的乘车人", "看看有没有张三这个乘车人"),
                    List.of("给张三买票", "退掉张三的票")),
            new IntentDefinition(
                    AgentIntent.ORDER_QUERY,
                    "查询当前账号的订单列表、订单详情或非支付专项的订单状态。",
                    List.of("查询我的订单", "看看订单 123 的详情"),
                    List.of("订单 123 支付成功了吗", "取消订单 123")),
            new IntentDefinition(
                    AgentIntent.PAYMENT_QUERY,
                    "用户明确询问指定订单是否支付成功、支付状态或支付结果。",
                    List.of("订单 123 支付成功了吗", "查询这个订单的支付结果"),
                    List.of("查询订单详情", "取消未支付订单")),
            new IntentDefinition(
                    AgentIntent.TICKET_PURCHASE,
                    "用户明确要求购买、预订或下单，或在已有查票结果中选定车次和席别继续购票。",
                    List.of("给张三买明天北京到上海的票", "购买最早的一班二等座"),
                    List.of("查一下有没有票", "G1 票价多少")),
            new IntentDefinition(
                    AgentIntent.ORDER_CANCELLATION,
                    "取消尚未支付的整个订单，不允许指定单个乘车人；用户明确说取消订单且未指定乘车人时使用。",
                    List.of("取消订单 123", "把刚才那个未支付订单取消"),
                    List.of("退掉张三的票", "取消订单里张三的票")),
            new IntentDefinition(
                    AgentIntent.TICKET_REFUND,
                    "对已购车票发起全部或部分退票；即使用户使用“取消”，只要指定乘车人也属于退票。",
                    List.of("退掉订单 123 的全部车票", "取消张三的这张票"),
                    List.of("取消未支付的整个订单", "查询退票订单详情")));

    /**
     * 返回完整且不可变的意图定义集合。
     *
     * @return 按稳定顺序排列的意图定义
     */
    public List<IntentDefinition> definitions() {
        // 集合在初始化时已经不可变，可安全供规划器和测试读取。
        return definitions;
    }

    /**
     * 将意图目录转换为可嵌入系统提示词的受控文本。
     *
     * @return 每行包含意图名称和业务描述的提示文本
     */
    public String toPromptText() {
        // 正例和排除项帮助小模型区分相邻意图，但不暴露任何工具或执行实现。
        return definitions.stream()
                .map(definition -> "- "
                        + definition.name().name()
                        + "："
                        + definition.description()
                        + "\n  正例："
                        + String.join("；", definition.examples())
                        + "\n  不适用："
                        + String.join("；", definition.exclusions()))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow(() -> new IllegalStateException("意图目录不能为空"));
    }
}
