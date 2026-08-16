/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.common.constant;

/**
 * Redis Key 定义常量类
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public final class RedisKeyConstant {

    /**
     * 列车基本信息，Key Prefix + 列车ID
     */
    public static final String TRAIN_INFO = "index12306-ticket-service:train_info:";

    /**
     * 地区与站点映射查询
     */
    public static final String REGION_TRAIN_STATION_MAPPING = "index12306-ticket-service:region_train_station_mapping";

    /**
     * 站点查询分布式锁 Key
     */
    public static final String LOCK_REGION_TRAIN_STATION_MAPPING = "index12306-ticket-service:lock:region_train_station_mapping";

    /**
     * 站点查询，Key Prefix + 起始城市_终点城市_日期
     */
    public static final String REGION_TRAIN_STATION = "index12306-ticket-service:region_train_station:%s_%s";

    /**
     * 站点查询分布式锁 Key
     */
    public static final String LOCK_REGION_TRAIN_STATION = "index12306-ticket-service:lock:region_train_station";

    /**
     * 列车站点座位价格查询，Key Prefix + 列车ID_起始城市_终点城市
     */
    public static final String TRAIN_STATION_PRICE = "index12306-ticket-service:train_station_price:%s_%s_%s";

    /**
     * 地区以及车站查询，Key Prefix + ( 车站名称 or 查询方式 )
     */
    public static final String REGION_STATION = "index12306-ticket-service:region-station:";

    /**
     * 站点余票查询，Key Prefix + 列车ID_起始站点_终点
     */
    public static final String TRAIN_STATION_REMAINING_TICKET = "index12306-ticket-service:train_station_remaining_ticket:";

    /**
     * 列车车厢查询，Key Prefix + 列车ID
     */
    public static final String TRAIN_CARRIAGE = "index12306-ticket-service:train_carriage:";

    /**
     * 车厢余票查询，Key Prefix + 列车ID_起始站点_终点
     */
    public static final String TRAIN_STATION_CARRIAGE_REMAINING_TICKET = "index12306-ticket-service:train_station_carriage_remaining_ticket:";

    /**
     * 站点详细信息查询，Key Prefix + 列车ID_起始站点_终点
     */
    public static final String TRAIN_STATION_DETAIL = "index12306-ticket-service:train_station_detail:";

    /**
     * 列车路线信息查询，Key Prefix + 列车ID
     */
    public static final String TRAIN_STATION_STOPOVER_DETAIL = "index12306-ticket-service:train_station_stopover_detail:";

    /**
     * 列车站点缓存
     */
    public static final String STATION_ALL = "index12306-ticket-service:all_station";

    /**
     * 列车车厢状态， Key Prefix + 列车 ID + 起始站点 + 目的站点 + 车厢编号
     */
    public static final String TRAIN_CARRIAGE_SEAT_STATUS = "index12306-ticket-service:train_carriage_seat_status:";

    /**
     * 用户购票分布式锁 Key v2
     */
    public static final String LOCK_PURCHASE_TICKETS_V2 = "${unique-name:}index12306-ticket-service:lock:purchase_tickets_%s_%d";

    /**
     * 用户购票位图锁 Key v2（车次 + 座位类型）
     */
    public static final String LOCK_PURCHASE_TICKETS_V2_BITMAP = "${unique-name:}index12306-ticket-service:lock:purchase_tickets_bitmap_%s_%d";

    /**
     * 用户购票分布式锁 Key v2（区间粒度）
     */
    public static final String LOCK_PURCHASE_TICKETS_V2_SEGMENT = "${unique-name:}index12306-ticket-service:lock:purchase_tickets_%s_%d_%s_%s";

    /**
     * 购票主资源锁：车厢 + 基础区间段粒度
     */
    public static final String LOCK_PURCHASE_TICKETS_RESOURCE_SEGMENT = "${unique-name:}index12306-ticket-service:lock:purchase_tickets_resource_%s_%s_%d_%s_%d";

    /**
     * 车厢维度余票粗筛游标
     */
    public static final String TRAIN_STATION_CARRIAGE_REMAINING_TICKET_CURSOR = "index12306-ticket-service:train_station_carriage_remaining_ticket_cursor:";

    public static final String TRAIN_CARRIAGE_SEAT_ALLOCATION_CURSOR = "index12306-ticket-service:train_carriage_seat_allocation_cursor:";

    /**
     * 车厢区间段座位占用位图，使用 Redis Cluster hash tag 保证同车厢多段 key 落在同一 slot
     */
    public static final String TRAIN_CARRIAGE_SEGMENT_SEAT_BITMAP = "index12306-ticket-service:seat_bitmap:{%s:%d:%s}:%d";

    /**
     * 车厢区间段临时占座归属，field 为座位 bit，value 为 holdId
     */
    public static final String TRAIN_CARRIAGE_SEGMENT_SEAT_OWNER = "index12306-ticket-service:seat_owner:{%s:%d:%s}:%d";

    /**
     * 带列车始发日期的车厢区间座位占用位图。
     */
    public static final String TRAIN_CARRIAGE_SEGMENT_SEAT_BITMAP_BY_SERVICE_DATE = "index12306-ticket-service:seat_bitmap:{%s:%s:%d:%s}:%d";

    /**
     * 带列车始发日期的车厢区间座位归属映射。
     */
    public static final String TRAIN_CARRIAGE_SEGMENT_SEAT_OWNER_BY_SERVICE_DATE = "index12306-ticket-service:seat_owner:{%s:%s:%d:%s}:%d";

    /**
     * 座位策略百毫秒统计桶，同一库存维度的全部窗口桶共享一个 Hash Tag。
     */
    public static final String SEAT_SELECTION_STRATEGY_BUCKET = "index12306-ticket-service:seat_strategy:bucket:{%s}:%s:%d";

    /**
     * 座位策略统计桶中的 reservation 近似去重集合。
     */
    public static final String SEAT_SELECTION_STRATEGY_RESERVATIONS = "index12306-ticket-service:seat_strategy:reservations:{%s}:%s:%d";

    /**
     * 座位策略共享状态机，同一库存维度的状态与统计桶使用相同 Hash Tag。
     */
    public static final String SEAT_SELECTION_STRATEGY_STATE = "index12306-ticket-service:seat_strategy:state:{%s}";

    /**
     * 获取全部地点集合 Key
     */
    public static final String QUERY_ALL_REGION_LIST = "index12306-ticket-service:query_all_region_list";

    /**
     * 列车购买令牌桶，Key Prefix + 列车ID
     */
    public static final String TICKET_AVAILABILITY_TOKEN_BUCKET = "index12306-ticket-service:ticket_availability_token_bucket:";

    /**
     * reservation 令牌桶回滚标记，reservationId 作为稳定去重键
     */
    public static final String TICKET_RESERVATION_TOKEN_ROLLBACK_MARKER = "index12306-ticket-service:ticket_reservation_token_rollback:%s";

    /**
     * 获取全部地点集合分布式锁 Key
     */
    public static final String LOCK_QUERY_ALL_REGION_LIST = "index12306-ticket-service:lock:query_all_region_list";

    /**
     * 获取列车车厢数量集合分布式锁 Key
     */
    public static final String LOCK_QUERY_CARRIAGE_NUMBER_LIST = "index12306-ticket-service:lock:query_carriage_number_list_%s";

    /**
     * 获取地区以及站点集合分布式锁 Key
     */
    public static final String LOCK_QUERY_REGION_STATION_LIST = "index12306-ticket-service:lock:query_region_station_list_%s";

    /**
     * 获取相邻座位余票分布式锁 Key
     */
    public static final String LOCK_SAFE_LOAD_SEAT_MARGIN_GET = "index12306-ticket-service:lock:safe_load_seat_margin_%s";

    /**
     * 列车购买令牌桶加载数据 Key
     */
    public static final String LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET = "index12306-ticket-service:lock:ticket_availability_token_bucket:%s";

    /**
     * 令牌获取失败分布式锁 Key
     */
    public static final String LOCK_TOKEN_BUCKET_ISNULL = "index12306-ticket-service:lock:token-bucket-isnull:%s";
}
