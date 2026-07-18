package org.opengoofy.index12306.ai.agentservice.mcp.context;

import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将显式 Agent 请求上下文转换为 Spring AI 工具上下文，不依赖线程本地状态。
 */
@Component
public class McpToolContextFactory {

    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String USERNAME = "username";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String TURN_ID = "turnId";
    public static final String ACTION_ID = "actionId";
    public static final String PAYLOAD_HASH = "payloadHash";

    /**
     * 生成模型不可编辑、随后会被签名的工具上下文属性。
     *
     * @param context 当前 Agent 请求上下文
     * @return 可传给 ChatClient toolContext 的不可变属性
     */
    public Map<String, Object> create(AgentRequestContext context) {
        // 仅放入身份和审计关联字段，业务工具参数仍由模型独立生成。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(REQUEST_ID, context.requestId());
        result.put(USER_ID, context.userId());
        result.put(USERNAME, valueOrEmpty(context.username()));
        result.put(CONVERSATION_ID, context.conversationId());
        result.put(TURN_ID, context.turnId());
        return Map.copyOf(result);
    }

    /**
     * 生成仅供确认执行器使用的写操作工具上下文。
     *
     * @param context 当前 Agent 请求上下文
     * @param actionId 已消费确认令牌的草案标识
     * @param payloadHash 数据库中不可变购票参数指纹
     * @return 包含写操作证明字段的不可变上下文
     */
    public Map<String, Object> createConfirmedAction(
            AgentRequestContext context,
            String actionId,
            String payloadHash) {
        // 先复用身份字段，再加入只由服务端状态机提供的操作证明关联字段。
        Map<String, Object> result = new LinkedHashMap<>(create(context));
        result.put(ACTION_ID, actionId);
        result.put(PAYLOAD_HASH, payloadHash);
        return Map.copyOf(result);
    }

    /**
     * 将允许为空的上下文字段规范为稳定空字符串。
     *
     * @param value 原始字段值
     * @return 原值或空字符串
     */
    private String valueOrEmpty(String value) {
        // 签名两端对缺失可选字段使用同一空值表示。
        return value == null ? "" : value;
    }
}
