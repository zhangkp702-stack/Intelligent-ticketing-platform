package org.opengoofy.index12306.ai.mcpserver.tool;

import java.util.List;

/**
 * 票务 MCP 工具使用的脱敏、限量响应结构。
 */
public final class TicketToolResult {

    /**
     * 禁止实例化仅用于承载工具响应类型的容器类。
     */
    private TicketToolResult() {
    }

    /**
     * @param name 站点名称
     * @param code 站点编码
     * @param spell 站点拼音
     */
    public record StationMatch(String name, String code, String spell) {
    }

    /**
     * @param trains 符合条件的车次
     * @param truncated 是否因工具结果上限被截断
     */
    public record TicketSearchResult(List<TrainTicket> trains, boolean truncated) {
    }

    /**
     * @param trainId 列车内部标识
     * @param trainNumber 车次号
     * @param departureTime 出发时间
     * @param arrivalTime 到达时间
     * @param duration 历时
     * @param daysArrived 到达所需天数
     * @param departure 出发站
     * @param arrival 到达站
     * @param saleTime 开售时间
     * @param saleStatus 售卖状态
     * @param seats 各席别余票与价格
     */
    public record TrainTicket(
            String trainId,
            String trainNumber,
            String departureTime,
            String arrivalTime,
            String duration,
            Integer daysArrived,
            String departure,
            String arrival,
            String saleTime,
            Integer saleStatus,
            List<SeatAvailability> seats) {
    }

    /**
     * @param type 席别编码
     * @param quantity 余票数量
     * @param price 票价
     * @param candidate 是否支持候补
     */
    public record SeatAvailability(Integer type, Integer quantity, String price, Boolean candidate) {
    }

    /**
     * @param sequence 经停顺序
     * @param stationName 站名
     * @param arrivalTime 到站时间
     * @param departureTime 发车时间
     * @param stopoverMinutes 停留分钟数
     */
    public record TrainStop(
            String sequence,
            String stationName,
            String arrivalTime,
            String departureTime,
            Integer stopoverMinutes) {
    }

    /**
     * @param passengerId 乘车人标识
     * @param realName 姓名
     * @param idType 证件类型
     * @param maskedIdCard 脱敏证件号
     * @param discountType 优惠类型
     * @param maskedPhone 脱敏手机号
     * @param verifyStatus 审核状态
     */
    public record PassengerView(
            String passengerId,
            String realName,
            Integer idType,
            String maskedIdCard,
            Integer discountType,
            String maskedPhone,
            Integer verifyStatus) {
    }

    /**
     * @param current 当前页
     * @param size 每页数量
     * @param total 总记录数
     * @param orders 本人订单记录
     */
    public record OrderPage(long current, long size, long total, List<OrderView> orders) {
    }

    /**
     * @param departure 出发站
     * @param arrival 到达站
     * @param ridingDate 乘车日期
     * @param trainNumber 车次号
     * @param departureTime 出发时间
     * @param arrivalTime 到达时间
     * @param seatType 席别编码
     * @param carriageNumber 车厢号
     * @param seatNumber 座位号
     * @param realName 乘车人姓名
     * @param ticketType 票种
     * @param amount 订单金额，单位沿用订单服务定义
     */
    public record OrderView(
            String departure,
            String arrival,
            String ridingDate,
            String trainNumber,
            String departureTime,
            String arrivalTime,
            Integer seatType,
            String carriageNumber,
            String seatNumber,
            String realName,
            Integer ticketType,
            Integer amount) {
    }

    /**
     * @param passengerId 当前用户乘车人标识
     * @param seatType 席别编码
     */
    public record ConfirmedPurchasePassenger(String passengerId, Integer seatType) {
    }

    /**
     * @param orderSn 新建订单号
     * @param tickets 不包含证件号的车票明细
     */
    public record ConfirmedPurchaseResult(String orderSn, List<PurchasedTicketView> tickets) {
    }

    /**
     * @param seatType 席别编码
     * @param carriageNumber 车厢号
     * @param seatNumber 座位号
     * @param realName 乘车人姓名
     * @param ticketType 票种
     * @param amount 金额，单位沿用票务服务定义
     */
    public record PurchasedTicketView(
            Integer seatType,
            String carriageNumber,
            String seatNumber,
            String realName,
            Integer ticketType,
            Integer amount) {
    }
}
