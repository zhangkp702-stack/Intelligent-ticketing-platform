package org.opengoofy.index12306.ai.agentservice.action.enums;


import java.util.Arrays;

/**
 * 提供给购票草案工具的语义化席别，并集中维护票务服务席别编码映射。
 */
public enum PurchaseSeatClass {

    BUSINESS_CLASS(0, "商务座"),
    FIRST_CLASS(1, "一等座"),
    SECOND_CLASS(2, "二等座"),
    SECOND_CLASS_CABIN_SEAT(3, "二等包座"),
    FIRST_SLEEPER(4, "一等卧"),
    SECOND_SLEEPER(5, "二等卧"),
    SOFT_SLEEPER(6, "软卧"),
    HARD_SLEEPER(7, "硬卧"),
    HARD_SEAT(8, "硬座"),
    DELUXE_SOFT_SLEEPER(9, "高级软卧"),
    DINING_CAR_SLEEPER(10, "动卧"),
    SOFT_SEAT(11, "软座"),
    FIRST_CLASS_SEAT(12, "特等座"),
    NO_SEAT_SLEEPER(13, "无座"),
    OTHER(14, "其他");

    private final int code;
    private final String label;

    /**
     * 创建语义席别与票务服务编码的固定映射。
     *
     * @param code 票务服务席别编码
     * @param label 用户可读席别名称
     */
    PurchaseSeatClass(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 返回调用票务服务时使用的稳定席别编码。
     *
     * @return 票务服务席别编码
     */
    public int code() {
        return code;
    }

    /**
     * 返回确认卡片展示的席别名称。
     *
     * @return 用户可读席别名称
     */
    public String label() {
        return label;
    }

    /**
     * 根据持久化编码查找语义席别，拒绝未定义的模型输出。
     *
     * @param code 票务服务席别编码
     * @return 对应的语义席别
     */
    public static PurchaseSeatClass fromCode(Integer code) {
        // 只接受当前票务服务明确公开的编码，避免仅凭数值范围放行未知席别。
        return Arrays.stream(values())
                .filter(seatClass -> code != null && seatClass.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("席别编码不正确"));
    }
}
