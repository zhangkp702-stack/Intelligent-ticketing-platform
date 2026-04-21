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
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;

import java.util.List;

/**
 * 座位持久层
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public interface SeatMapper extends BaseMapper<SeatDO> {

    /**
     * 获取列车车厢余票集合
     */
    List<Integer> listSeatRemainingTicket(@Param("trainId") Long trainId, @Param("requestMask") Long requestMask, @Param("trainCarriageList") List<String> trainCarriageList);

    /**
     * 获取列车区间可用座位数量
     */
    List<SeatTypeCountDTO> listSeatTypeCount(@Param("trainId") Long trainId, @Param("requestMask") Long requestMask, @Param("seatTypes") List<Integer> seatTypes);

    /**
     * 获取可用座位（按车厢）
     */
    List<SeatDO> listAvailableSeatByCarriage(@Param("trainId") Long trainId,
                                             @Param("carriageNumber") String carriageNumber,
                                             @Param("seatType") Integer seatType,
                                             @Param("requestMask") Long requestMask,
                                             @Param("limit") Integer limit);

    /**
     * 获取可用车厢号集合
     */
    List<String> listUsableCarriageNumber(@Param("trainId") Long trainId,
                                          @Param("seatType") Integer seatType,
                                          @Param("requestMask") Long requestMask);

    /**
     * 车厢维度余票粗筛摘要
     */
    List<CarriageAvailabilityDTO> listCarriageAvailabilitySummary(@Param("trainId") Long trainId,
                                                                  @Param("seatType") Integer seatType,
                                                                  @Param("requestMask") Long requestMask);

    /**
     * 单座位原子占位
     */
    int tryLockSeatByBitmap(@Param("seatId") Long seatId,
                            @Param("version") Long version,
                            @Param("requestMask") Long requestMask);

    /**
     * 单座位原子解锁（幂等）
     */
    int unlockSeatByBitmap(@Param("seatId") Long seatId,
                           @Param("requestMask") Long requestMask);
}
