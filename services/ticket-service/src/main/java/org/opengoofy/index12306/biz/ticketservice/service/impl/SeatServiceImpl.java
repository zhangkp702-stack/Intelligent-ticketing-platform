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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 座位接口层实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatServiceImpl extends ServiceImpl<SeatMapper, SeatDO> implements SeatService {

    private final SeatMapper seatMapper;
    private final TrainStationService trainStationService;

    @Override
    public List<String> listAvailableSeat(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        List<SeatDO> availableSeats = seatMapper.listAvailableSeatByCarriage(
                Long.parseLong(trainId), carriageNumber, seatType, requestMask, 1000);
        return availableSeats.stream().map(SeatDO::getSeatNumber).collect(Collectors.toList());
    }

    @Override
    public List<Integer> listSeatRemainingTicket(String trainId, String departure, String arrival, List<String> trainCarriageList) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        return seatMapper.listSeatRemainingTicket(Long.parseLong(trainId), requestMask, trainCarriageList);
    }

    @Override
    public List<String> listUsableCarriageNumber(String trainId, Integer carriageType, String departure, String arrival) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        return seatMapper.listUsableCarriageNumber(Long.parseLong(trainId), carriageType, requestMask);
    }

    @Override
    public List<SeatTypeCountDTO> listSeatTypeCount(Long trainId, String startStation, String endStation, List<Integer> seatTypes) {
        long requestMask = buildRequestMask(String.valueOf(trainId), startStation, endStation);
        return seatMapper.listSeatTypeCount(trainId, requestMask, seatTypes);
    }

    @Override
    public boolean tryLockSeat(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        List<Long> lockedSeatIds = new ArrayList<>();
        Long trainIdLong = Long.parseLong(trainId);
        for (TrainPurchaseTicketRespDTO ticket : tickets) {
            SeatDO seat = seatMapper.selectOne(Wrappers.lambdaQuery(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainIdLong)
                    .eq(SeatDO::getCarriageNumber, ticket.getCarriageNumber())
                    .eq(SeatDO::getSeatNumber, ticket.getSeatNumber())
                    .eq(SeatDO::getSeatType, ticket.getSeatType()));
            if (seat == null) {
                rollbackLockedSeats(lockedSeatIds, requestMask);
                return false;
            }
            int updated = seatMapper.tryLockSeatByBitmap(seat.getId(), seat.getVersion(), requestMask);
            if (updated <= 0) {
                rollbackLockedSeats(lockedSeatIds, requestMask);
                return false;
            }
            lockedSeatIds.add(seat.getId());
        }
        return true;
    }

    @Override
    public void lockSeat(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        if (!tryLockSeat(trainId, departure, arrival, tickets)) {
            throw new IllegalStateException("座位锁定失败");
        }
    }

    @Override
    public void unlock(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        Long trainIdLong = Long.parseLong(trainId);
        for (TrainPurchaseTicketRespDTO ticket : tickets) {
            LambdaQueryWrapper<SeatDO> queryWrapper = Wrappers.lambdaQuery(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainIdLong)
                    .eq(SeatDO::getCarriageNumber, ticket.getCarriageNumber())
                    .eq(SeatDO::getSeatNumber, ticket.getSeatNumber())
                    .eq(SeatDO::getSeatType, ticket.getSeatType());
            SeatDO seat = seatMapper.selectOne(queryWrapper);
            if (seat != null) {
                seatMapper.unlockSeatByBitmap(seat.getId(), requestMask);
            }
        }
    }

    private long buildRequestMask(String trainId, String departure, String arrival) {
        List<String> stationNames = trainStationService.listTrainStationNameByTrainId(trainId);
        return StationSegmentBitmapUtil.buildRequestMask(stationNames, departure, arrival);
    }

    private void rollbackLockedSeats(List<Long> lockedSeatIds, long requestMask) {
        for (Long seatId : lockedSeatIds) {
            try {
                seatMapper.unlockSeatByBitmap(seatId, requestMask);
            } catch (Exception ex) {
                log.error("回滚已锁定座位失败 seatId={}", seatId, ex);
            }
        }
    }
}
