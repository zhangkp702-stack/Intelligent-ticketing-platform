package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 将已经校验的业务意图确定性映射到固定执行链。
 */
@Service
public class IntentExecutionRoutingService {

    /**
     * 根据受控意图选择唯一执行链和业务分组。
     *
     * @param intent 已通过任务规划校验的业务意图
     * @return 不包含任何模型工具定义的执行决策
     */
    public IntentExecutionDecision route(AgentIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("业务意图不能为空");
        }

        // 路由只消费枚举值，不读取用户文本，也不允许模型再次选择执行方式。
        return switch (intent) {
            case GENERAL_CHAT -> new IntentExecutionDecision(
                    IntentExecutionRoute.CHAT_ONLY, intent, Set.of());
            case TRAIN_QUERY -> readOnly(intent, BusinessGroup.TRAIN_QUERY);
            case TRAIN_STOP_QUERY -> readOnly(intent, BusinessGroup.TRAIN_STOP);
            case PASSENGER_QUERY -> readOnly(intent, BusinessGroup.PASSENGER);
            case ORDER_QUERY -> readOnly(intent, BusinessGroup.ORDER_QUERY);
            case PAYMENT_QUERY -> new IntentExecutionDecision(
                    IntentExecutionRoute.READ_ONLY_CODE_CHAIN,
                    intent,
                    Set.of(BusinessGroup.ORDER_QUERY, BusinessGroup.PAYMENT));
            case TICKET_PURCHASE -> transaction(intent, BusinessGroup.PURCHASE);
            case ORDER_CANCELLATION -> transaction(intent, BusinessGroup.CANCELLATION);
            case TICKET_REFUND -> transaction(intent, BusinessGroup.REFUND);
        };
    }

    /**
     * 判断指定意图是否必须进入串行交易固定链。
     *
     * @param intent 已校验业务意图
     * @return 是否属于会生成待确认草案的交易链
     */
    public boolean isTransaction(AgentIntent intent) {
        // 交易属性由统一路由结果决定，避免调用方重复维护枚举列表。
        return route(intent).route() == IntentExecutionRoute.TRANSACTION_CODE_CHAIN;
    }

    /**
     * 创建单业务组的只读固定链决策。
     *
     * @param intent 当前只读意图
     * @param group 对应业务组
     * @return 只读固定链决策
     */
    private IntentExecutionDecision readOnly(
            AgentIntent intent,
            BusinessGroup group) {
        // 只读链由服务端实现直接执行，不向最终回答模型注册工具。
        return new IntentExecutionDecision(
                IntentExecutionRoute.READ_ONLY_CODE_CHAIN, intent, Set.of(group));
    }

    /**
     * 创建单业务组的交易固定链决策。
     *
     * @param intent 当前交易意图
     * @param group 对应业务组
     * @return 交易固定链决策
     */
    private IntentExecutionDecision transaction(
            AgentIntent intent,
            BusinessGroup group) {
        // 交易链只生成待确认草案，真实写操作仍由独立确认入口执行。
        return new IntentExecutionDecision(
                IntentExecutionRoute.TRANSACTION_CODE_CHAIN, intent, Set.of(group));
    }

    /**
     * 区分普通问答、只读固定链和交易固定链。
     */
    public enum IntentExecutionRoute {
        CHAT_ONLY,
        READ_ONLY_CODE_CHAIN,
        TRANSACTION_CODE_CHAIN
    }

    /**
     * 可独立执行和观测的票务业务分组。
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
     * 意图识别后由服务端生成的固定执行决策。
     *
     * @param route 执行链类型
     * @param intent 已识别意图
     * @param matchedGroups 本轮涉及的业务组
     */
    public record IntentExecutionDecision(
            IntentExecutionRoute route,
            AgentIntent intent,
            Set<BusinessGroup> matchedGroups) {
    }
}
