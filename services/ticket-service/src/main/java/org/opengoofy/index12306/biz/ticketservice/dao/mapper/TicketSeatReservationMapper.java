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

package org.opengoofy.index12306.biz.ticketservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketSeatReservationDO;

import java.util.Date;
import java.util.List;

/**
 * 订单座位占用记录持久层。
 */
public interface TicketSeatReservationMapper extends BaseMapper<TicketSeatReservationDO> {

    /**
     * 查询订单全部有效座位占用记录。
     *
     * @param orderSn 已关闭订单号
     * @return 订单关联的座位占用记录
     */
    @Select("SELECT * FROM t_ticket_seat_reservation WHERE order_sn = #{orderSn} "
            + "AND reservation_status = 1 AND del_flag = 0 ORDER BY id")
    List<TicketSeatReservationDO> selectByOrderSn(@Param("orderSn") String orderSn);

    /**
     * 锁定一条座位占用记录，串行化数据库座位释放与步骤状态更新。
     *
     * @param reservationId 座位占用标识
     * @return 当前占用记录，不存在时返回空
     */
    @Select("SELECT * FROM t_ticket_seat_reservation WHERE reservation_id = #{reservationId} AND del_flag = 0 FOR UPDATE")
    TicketSeatReservationDO selectByReservationIdForUpdate(@Param("reservationId") String reservationId);

    /**
     * 按受理标识和用户归属查询座位占用记录。
     *
     * @param reservationId 购票受理标识
     * @param userId 当前用户标识
     * @return 当前用户的座位占用记录，不存在或不属于当前用户时返回空
     */
    @Select("SELECT * FROM t_ticket_seat_reservation WHERE reservation_id = #{reservationId} "
            + "AND user_id = #{userId} AND del_flag = 0")
    TicketSeatReservationDO selectByReservationIdAndUserId(@Param("reservationId") String reservationId,
                                                            @Param("userId") String userId);

    /**
     * 查询已绑定订单且释放步骤长期未完成的 reservation，供关闭事件丢失后的定时恢复使用。
     *
     * @param deadline 最后更新时间截止点
     * @param limit 单次恢复上限
     * @return 仍有待处理释放步骤的座位占用记录
     */
    @Select("SELECT * FROM t_ticket_seat_reservation "
            + "WHERE del_flag = 0 AND reservation_status = 1 AND order_sn IS NOT NULL "
            + "AND update_time <= #{deadline} "
            + "AND (db_seat_release_status = 0 OR redis_bitmap_release_status = 0 OR token_rollback_status = 0) "
            + "ORDER BY update_time ASC, id ASC LIMIT #{limit}")
    List<TicketSeatReservationDO> selectStaleIncompleteReservations(@Param("deadline") Date deadline,
                                                                     @Param("limit") int limit);

    /**
     * 查询超时但尚未绑定订单的 reservation，供订单命令对账决定补绑或失败释放。
     *
     * @param deadline 最后更新时间截止点
     * @param limit 单次恢复上限
     * @return 仍持有任一资源步骤的 PREPARED，或尚未写入最终状态的 RELEASING reservation
     */
    @Select("SELECT * FROM t_ticket_seat_reservation "
            + "WHERE del_flag = 0 AND reservation_status IN (0, 2) "
            + "AND command_id IS NOT NULL AND user_id IS NOT NULL AND update_time <= #{deadline} "
            + "AND (reservation_status = 2 OR db_seat_release_status = 0 "
            + "OR redis_bitmap_release_status = 0 OR token_rollback_status = 0) "
            + "ORDER BY update_time ASC, id ASC LIMIT #{limit}")
    List<TicketSeatReservationDO> selectStalePreparedReservations(@Param("deadline") Date deadline,
                                                                   @Param("limit") int limit);

    /**
     * 查询仅由压测账号产生、未写入建单 Outbox 且已超时的孤儿预占记录。
     *
     * @param deadline 最后更新时间截止点
     * @param limit 单次释放上限
     * @return 可在隔离压测环境中直接回收的座位占用记录
     */
    @Select("SELECT r.* FROM t_ticket_seat_reservation r "
            + "WHERE r.del_flag = 0 AND r.reservation_status = 0 "
            + "AND r.username LIKE 'loadtest%' AND r.update_time <= #{deadline} "
            + "AND r.db_seat_release_status = 0 AND r.redis_bitmap_release_status = 0 "
            + "AND r.token_rollback_status = 0 "
            + "AND NOT EXISTS (SELECT 1 FROM t_reliable_outbox_event e "
            + "WHERE e.namespace = 'ticket-order-creation' AND e.event_id = r.reservation_id) "
            + "ORDER BY r.update_time ASC, r.id ASC LIMIT #{limit}")
    List<TicketSeatReservationDO> selectStaleLoadTestPreparedReservationsWithoutOutbox(
            @Param("deadline") Date deadline, @Param("limit") int limit);

    /**
     * 统计已绑定订单中超过恢复宽限期且仍有资源步骤未完成的 reservation。
     *
     * @param deadline 最后更新时间截止点
     * @return 已绑定订单释放积压数量
     */
    @Select("SELECT COUNT(1) FROM t_ticket_seat_reservation "
            + "WHERE del_flag = 0 AND reservation_status = 1 AND order_sn IS NOT NULL "
            + "AND update_time <= #{deadline} "
            + "AND (db_seat_release_status = 0 OR redis_bitmap_release_status = 0 OR token_rollback_status = 0)")
    long countStaleIncompleteReservations(@Param("deadline") Date deadline);

    /**
     * 统计可自动查询稳定命令终态的超时 PREPARED reservation。
     *
     * @param deadline 最后更新时间截止点
     * @return 等待订单命令对账的 PREPARED 积压数量
     */
    @Select("SELECT COUNT(1) FROM t_ticket_seat_reservation "
            + "WHERE del_flag = 0 AND reservation_status = 0 AND command_id IS NOT NULL AND command_id <> '' "
            + "AND user_id IS NOT NULL AND user_id <> '' "
            + "AND update_time <= #{deadline}")
    long countStalePreparedReservations(@Param("deadline") Date deadline);

    /**
     * 统计已领取失败释放权但仍未写入 RELEASED 终态的 reservation。
     *
     * @param deadline 最后更新时间截止点
     * @return 失败释放中的积压数量
     */
    @Select("SELECT COUNT(1) FROM t_ticket_seat_reservation "
            + "WHERE del_flag = 0 AND reservation_status = 2 AND update_time <= #{deadline}")
    long countStaleReleasingReservations(@Param("deadline") Date deadline);

    /**
     * 统计缺少自动命令对账所需归属键、必须人工处理的超时 PREPARED reservation。
     *
     * @param deadline 最后更新时间截止点
     * @return 缺少命令或用户归属键的积压数量
     */
    @Select("SELECT COUNT(1) FROM t_ticket_seat_reservation "
            + "WHERE del_flag = 0 AND reservation_status = 0 AND update_time <= #{deadline} "
            + "AND (command_id IS NULL OR command_id = '' OR user_id IS NULL OR user_id = '')")
    long countStalePreparedReservationsMissingReconcileKey(@Param("deadline") Date deadline);
}
