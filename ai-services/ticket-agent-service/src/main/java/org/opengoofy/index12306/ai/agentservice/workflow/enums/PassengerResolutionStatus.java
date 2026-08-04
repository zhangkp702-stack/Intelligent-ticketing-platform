package org.opengoofy.index12306.ai.agentservice.workflow.enums;

/**
 * 表示购票乘车人名称解析后的服务端处理结果。
 */
public enum PassengerResolutionStatus {
    /** 用户尚未提供乘车人姓名。 */
    NAME_REQUIRED,
    /** 未找到匹配的常用乘车人。 */
    NOT_FOUND,
    /** 已唯一确定乘车人。 */
    RESOLVED,
    /** 存在多个匹配乘车人，需要用户选择。 */
    SELECTION_REQUIRED,
    /** 当前用户没有可用乘车人。 */
    NO_PASSENGERS
}
