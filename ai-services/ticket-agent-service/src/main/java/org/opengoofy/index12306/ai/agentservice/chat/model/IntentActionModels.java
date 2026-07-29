package org.opengoofy.index12306.ai.agentservice.chat.model;

import java.util.List;

/**
 * 固定交易链消费的结构化业务字段。
 */
public final class IntentActionModels {

    /**
     * 阻止纯数据类型容器被实例化。
     */
    private IntentActionModels() {
        // 该类型只用于组织固定链共享的结构化记录。
    }

    /**
     * 购票固定链消费的结构化字段。
     *
     * @param departure 出发站
     * @param arrival 到达站
     * @param departureDate 乘车日期
     * @param trainNumber 车次号
     * @param departureTime 出发时间
     * @param seatClass 席别名称
     * @param passengerNames 乘车人姓名
     */
    public record PurchaseIntentData(
            String departure,
            String arrival,
            String departureDate,
            String trainNumber,
            String departureTime,
            String seatClass,
            List<String> passengerNames) {
    }

    /**
     * 整单取消固定链消费的订单定位字段。
     *
     * @param orderSn 订单号
     * @param trainNumber 车次号
     * @param ridingDate 乘车日期
     * @param passengerNames 用户在取消表达中明确指定的乘车人
     */
    public record CancellationIntentData(
            String orderSn,
            String trainNumber,
            String ridingDate,
            List<String> passengerNames) {

        /**
         * 创建未指定乘车人的整单取消数据。
         *
         * @param orderSn 订单号
         * @param trainNumber 车次号
         * @param ridingDate 乘车日期
         */
        public CancellationIntentData(
                String orderSn,
                String trainNumber,
                String ridingDate) {
            // 旧调用方未提供乘车人时保持整单取消语义。
            this(orderSn, trainNumber, ridingDate, List.of());
        }
    }

    /**
     * 退票固定链消费的订单和乘车人字段。
     *
     * @param orderSn 订单号
     * @param trainNumber 车次号
     * @param ridingDate 乘车日期
     * @param passengerNames 需要退票的乘车人姓名
     */
    public record RefundIntentData(
            String orderSn,
            String trainNumber,
            String ridingDate,
            List<String> passengerNames) {
    }
}
