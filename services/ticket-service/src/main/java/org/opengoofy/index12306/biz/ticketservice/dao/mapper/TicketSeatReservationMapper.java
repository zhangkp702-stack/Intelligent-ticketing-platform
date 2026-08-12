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
    @Select("SELECT * FROM t_ticket_seat_reservation WHERE order_sn = #{orderSn} AND del_flag = 0 ORDER BY id")
    List<TicketSeatReservationDO> selectByOrderSn(@Param("orderSn") String orderSn);

    /**
     * 锁定一条座位占用记录，串行化数据库座位释放与步骤状态更新。
     *
     * @param reservationId 座位占用标识
     * @return 当前占用记录，不存在时返回空
     */
    @Select("SELECT * FROM t_ticket_seat_reservation WHERE reservation_id = #{reservationId} AND del_flag = 0 FOR UPDATE")
    TicketSeatReservationDO selectByReservationIdForUpdate(@Param("reservationId") String reservationId);
}
