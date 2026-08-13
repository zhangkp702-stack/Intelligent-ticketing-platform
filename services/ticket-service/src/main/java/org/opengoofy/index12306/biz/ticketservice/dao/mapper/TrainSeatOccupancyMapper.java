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
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainSeatOccupancyDO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;

import java.util.Date;
import java.util.List;

/**
 * 指定始发日期的列车座位运行库存持久层。
 */
public interface TrainSeatOccupancyMapper extends BaseMapper<TrainSeatOccupancyDO> {

    /**
     * 按静态座位布局创建指定始发日期的运行库存行，重复执行不会覆盖已占用状态。
     *
     * @param trainId 列车标识
     * @param serviceDate 始发日期
     * @return 本次新增的库存行数
     */
    int initializeServiceDateInventory(@Param("trainId") Long trainId, @Param("serviceDate") Date serviceDate);

    /**
     * 查询指定车厢可用于当前区间的座位布局。
     *
     * @param trainId 列车标识
     * @param serviceDate 始发日期
     * @param carriageNumber 车厢号
     * @param seatType 座位类型
     * @param requestMask 区间位图
     * @param limit 返回上限
     * @return 可用座位列表
     */
    List<SeatDO> listAvailableSeatByCarriage(@Param("trainId") Long trainId,
                                             @Param("serviceDate") Date serviceDate,
                                             @Param("carriageNumber") String carriageNumber,
                                             @Param("seatType") Integer seatType,
                                             @Param("requestMask") Long requestMask,
                                             @Param("limit") Integer limit);

    /**
     * 查询指定始发日期下各座位类型的可用数量。
     *
     * @param trainId 列车标识
     * @param serviceDate 始发日期
     * @param requestMask 区间位图
     * @param seatTypes 座位类型集合
     * @return 各座位类型余票数量
     */
    List<SeatTypeCountDTO> listSeatTypeCount(@Param("trainId") Long trainId,
                                             @Param("serviceDate") Date serviceDate,
                                             @Param("requestMask") Long requestMask,
                                             @Param("seatTypes") List<Integer> seatTypes);

    /**
     * 查询指定车厢在始发日期运行库存中的可用座位数。
     *
     * @param trainId 列车标识
     * @param serviceDate 始发日期
     * @param requestMask 区间位图
     * @param carriageNumbers 车厢号集合
     * @return 各车厢可用座位数
     */
    List<Integer> listSeatRemainingTicket(@Param("trainId") Long trainId,
                                          @Param("serviceDate") Date serviceDate,
                                          @Param("requestMask") Long requestMask,
                                          @Param("carriageNumbers") List<String> carriageNumbers);

    /**
     * 查询指定始发日期下车厢的可用座位摘要。
     *
     * @param trainId 列车标识
     * @param serviceDate 始发日期
     * @param seatType 座位类型
     * @param requestMask 区间位图
     * @return 车厢可用座位统计
     */
    List<CarriageAvailabilityDTO> listCarriageAvailabilitySummary(@Param("trainId") Long trainId,
                                                                  @Param("serviceDate") Date serviceDate,
                                                                  @Param("seatType") Integer seatType,
                                                                  @Param("requestMask") Long requestMask);

    /**
     * 在指定始发日期的运行库存中以版本号条件锁定一个座位。
     *
     * @param trainId 列车标识
     * @param serviceDate 始发日期
     * @param seatId 静态座位标识
     * @param version 当前版本号
     * @param requestMask 区间位图
     * @return 受影响行数
     */
    int tryLockSeatByBitmap(@Param("trainId") Long trainId,
                            @Param("serviceDate") Date serviceDate,
                            @Param("seatId") Long seatId,
                            @Param("version") Long version,
                            @Param("requestMask") Long requestMask);

    /**
     * 在指定始发日期的运行库存中幂等释放一个座位区间。
     *
     * @param trainId 列车标识
     * @param serviceDate 始发日期
     * @param seatId 静态座位标识
     * @param requestMask 区间位图
     * @return 受影响行数
     */
    int unlockSeatByBitmap(@Param("trainId") Long trainId,
                           @Param("serviceDate") Date serviceDate,
                           @Param("seatId") Long seatId,
                           @Param("requestMask") Long requestMask);
}
