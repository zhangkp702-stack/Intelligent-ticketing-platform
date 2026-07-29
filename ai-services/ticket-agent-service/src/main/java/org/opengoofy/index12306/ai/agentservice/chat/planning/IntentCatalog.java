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
                    "问候、能力介绍，或不属于下列票务业务的普通交流。"),
            new IntentDefinition(
                    AgentIntent.TRAIN_QUERY,
                    "查询车次、余票、票价、席别，或站点之间的可售车票。"),
            new IntentDefinition(
                    AgentIntent.TRAIN_STOP_QUERY,
                    "查询某趟列车的经停站、到发时刻或运行路线。"),
            new IntentDefinition(
                    AgentIntent.PASSENGER_QUERY,
                    "查询当前账号已经保存的乘车人。"),
            new IntentDefinition(
                    AgentIntent.ORDER_QUERY,
                    "查询当前账号的订单、订单详情或订单状态。"),
            new IntentDefinition(
                    AgentIntent.PAYMENT_QUERY,
                    "查询指定订单的支付状态或支付结果。"),
            new IntentDefinition(
                    AgentIntent.TICKET_PURCHASE,
                    "购买、预订、下单，或选定某趟车和席别继续购票。"),
            new IntentDefinition(
                    AgentIntent.ORDER_CANCELLATION,
                    "取消尚未支付的整个订单，不允许指定单个乘车人；指定乘车人时应选择 TICKET_REFUND。"),
            new IntentDefinition(
                    AgentIntent.TICKET_REFUND,
                    "对已购车票发起全部或部分退票；即使用户说“取消”，只要指定了乘车人也属于退票。"));

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
        // 只暴露业务名称和描述，不向规划模型提供任何工具或执行实现。
        return definitions.stream()
                .map(definition -> "- " + definition.name().name() + "：" + definition.description())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow(() -> new IllegalStateException("意图目录不能为空"));
    }
}
