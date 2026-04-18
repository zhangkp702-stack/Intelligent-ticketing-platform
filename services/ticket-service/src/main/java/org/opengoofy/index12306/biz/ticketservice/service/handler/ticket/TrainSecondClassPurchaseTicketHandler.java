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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.base.AbstractTrainPurchaseTicketTemplate;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatLayoutBitmapUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 高铁二等座购票组件
 * 完整版布局选座：优先精确选座，其次同排组合，再按窗口/过道偏好与邻近性回退。
 */
@Component
@RequiredArgsConstructor
public class TrainSecondClassPurchaseTicketHandler extends AbstractTrainPurchaseTicketTemplate {

    private final SeatService seatService;

    @Override
    public String mark() {
        return VehicleTypeEnum.HIGH_SPEED_RAIN.getName() + VehicleSeatTypeEnum.SECOND_CLASS.getName();
    }

    @Override
    protected List<TrainPurchaseTicketRespDTO> selectSeats(SelectSeatDTO requestParam) {
        return selectByLayout(requestParam, SeatLayoutBitmapUtil.profileBySeatType(VehicleSeatTypeEnum.SECOND_CLASS.getCode()));
    }

    private List<TrainPurchaseTicketRespDTO> selectByLayout(SelectSeatDTO requestParam, SeatLayoutBitmapUtil.LayoutProfile profile) {
        String trainId = requestParam.getRequestParam().getTrainId();
        String departure = requestParam.getRequestParam().getDeparture();
        String arrival = requestParam.getRequestParam().getArrival();
        List<PurchaseTicketPassengerDetailDTO> passengers = requestParam.getPassengerSeatDetails();
        List<String> carriageNumbers = seatService.listUsableCarriageNumber(trainId, requestParam.getSeatType(), departure, arrival);
        Set<String> excludedSeatNumbers = CollUtil.isEmpty(requestParam.getExcludeSeatNumbers())
                ? Set.of()
                : new LinkedHashSet<>(requestParam.getExcludeSeatNumbers());
        for (String carriageNumber : carriageNumbers) {
            List<String> availableSeats = seatService.listAvailableSeat(trainId, carriageNumber, requestParam.getSeatType(), departure, arrival).stream()
                    .filter(each -> !excludedSeatNumbers.contains(each))
                    .toList();
            if (availableSeats.size() < passengers.size()) {
                continue;
            }
            List<List<String>> candidates = buildSeatCandidates(availableSeats, requestParam.getRequestParam().getChooseSeats(), passengers.size(), profile);
            for (List<String> candidate : candidates) {
                if (candidate.size() == passengers.size()) {
                    return buildResult(passengers, carriageNumber, candidate);
                }
            }
        }
        throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
    }

    private List<List<String>> buildSeatCandidates(List<String> availableSeats, List<String> chooseSeats, int seatCount, SeatLayoutBitmapUtil.LayoutProfile profile) {
        Set<String> distinctAvailableSeats = new LinkedHashSet<>(availableSeats);
        List<List<String>> result = new ArrayList<>();
        List<String> exactChooseSeats = pickExactChooseSeats(distinctAvailableSeats, chooseSeats, seatCount);
        if (exactChooseSeats.size() == seatCount) {
            result.add(exactChooseSeats);
        }
        long availableMask = SeatLayoutBitmapUtil.buildAvailableMask(new ArrayList<>(distinctAvailableSeats), profile);
        List<SeatLayoutBitmapUtil.SeatCoordinate> sortedCoordinates = SeatLayoutBitmapUtil.decodeAvailableSeats(availableMask, profile).stream()
                .sorted(candidateComparator(profile))
                .toList();
        List<String> choosePlusFallback = mergeChooseAndFallback(distinctAvailableSeats, chooseSeats, sortedCoordinates, seatCount, profile);
        if (choosePlusFallback.size() == seatCount) {
            result.add(choosePlusFallback);
        }
        List<List<SeatLayoutBitmapUtil.SeatCoordinate>> adjacentCombos = SeatLayoutBitmapUtil.buildAdjacentCombos(availableMask, seatCount, profile).stream()
                .sorted(Comparator.comparingInt((List<SeatLayoutBitmapUtil.SeatCoordinate> each) -> score(each, profile)).reversed()
                        .thenComparingInt(each -> each.get(0).rowIndex())
                        .thenComparingInt(each -> each.get(0).colIndex()))
                .toList();
        adjacentCombos.forEach(each -> result.add(SeatLayoutBitmapUtil.toSeatNumbers(each, profile)));
        buildSameRowCombos(sortedCoordinates, seatCount).forEach(each -> result.add(SeatLayoutBitmapUtil.toSeatNumbers(each, profile)));
        buildGlobalCombos(sortedCoordinates, seatCount).forEach(each -> result.add(SeatLayoutBitmapUtil.toSeatNumbers(each, profile)));
        return result.stream()
                .filter(each -> each.size() == seatCount)
                .map(ArrayList::new)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> pickExactChooseSeats(Set<String> availableSeats, List<String> chooseSeats, int seatCount) {
        if (CollUtil.isEmpty(chooseSeats)) {
            return List.of();
        }
        return chooseSeats.stream().filter(availableSeats::contains).distinct().limit(seatCount).toList();
    }

    private List<String> mergeChooseAndFallback(Set<String> availableSeats, List<String> chooseSeats,
                                                List<SeatLayoutBitmapUtil.SeatCoordinate> sortedCoordinates,
                                                int seatCount, SeatLayoutBitmapUtil.LayoutProfile profile) {
        LinkedHashSet<String> result = new LinkedHashSet<>(pickExactChooseSeats(availableSeats, chooseSeats, seatCount));
        for (SeatLayoutBitmapUtil.SeatCoordinate coordinate : sortedCoordinates) {
            if (result.size() >= seatCount) {
                break;
            }
            result.add(SeatLayoutBitmapUtil.toSeatNumber(coordinate, profile));
        }
        return new ArrayList<>(result).subList(0, Math.min(result.size(), seatCount));
    }

    private List<List<SeatLayoutBitmapUtil.SeatCoordinate>> buildSameRowCombos(List<SeatLayoutBitmapUtil.SeatCoordinate> sortedCoordinates, int seatCount) {
        return sortedCoordinates.stream()
                .collect(Collectors.groupingBy(SeatLayoutBitmapUtil.SeatCoordinate::rowIndex))
                .values().stream()
                .filter(each -> each.size() >= seatCount)
                .map(each -> each.stream().limit(seatCount).toList())
                .toList();
    }

    private List<List<SeatLayoutBitmapUtil.SeatCoordinate>> buildGlobalCombos(List<SeatLayoutBitmapUtil.SeatCoordinate> sortedCoordinates, int seatCount) {
        List<List<SeatLayoutBitmapUtil.SeatCoordinate>> result = new ArrayList<>();
        for (int i = 0; i <= sortedCoordinates.size() - seatCount; i++) {
            result.add(sortedCoordinates.subList(i, i + seatCount));
        }
        return result;
    }

    private Comparator<SeatLayoutBitmapUtil.SeatCoordinate> candidateComparator(SeatLayoutBitmapUtil.LayoutProfile profile) {
        return Comparator.comparingInt((SeatLayoutBitmapUtil.SeatCoordinate each) -> seatPreferenceScore(each, profile)).reversed()
                .thenComparingInt(SeatLayoutBitmapUtil.SeatCoordinate::rowIndex)
                .thenComparingInt(SeatLayoutBitmapUtil.SeatCoordinate::colIndex);
    }

    private int score(List<SeatLayoutBitmapUtil.SeatCoordinate> coordinates, SeatLayoutBitmapUtil.LayoutProfile profile) {
        return coordinates.stream().mapToInt(each -> seatPreferenceScore(each, profile)).sum();
    }

    private int seatPreferenceScore(SeatLayoutBitmapUtil.SeatCoordinate coordinate, SeatLayoutBitmapUtil.LayoutProfile profile) {
        int bitIndex = coordinate.colIndex();
        int score = 10;
        if ((profile.windowMask() & (1L << bitIndex)) != 0) {
            score += 4;
        }
        if ((profile.aisleMask() & (1L << bitIndex)) != 0) {
            score += 2;
        }
        score -= coordinate.rowIndex();
        return score;
    }

    private List<TrainPurchaseTicketRespDTO> buildResult(List<PurchaseTicketPassengerDetailDTO> passengers, String carriageNumber, List<String> seatNumbers) {
        List<TrainPurchaseTicketRespDTO> results = new ArrayList<>(passengers.size());
        for (int i = 0; i < passengers.size(); i++) {
            PurchaseTicketPassengerDetailDTO passenger = passengers.get(i);
            TrainPurchaseTicketRespDTO ticket = new TrainPurchaseTicketRespDTO();
            ticket.setPassengerId(passenger.getPassengerId());
            ticket.setSeatType(passenger.getSeatType());
            ticket.setCarriageNumber(carriageNumber);
            ticket.setSeatNumber(seatNumbers.get(i));
            results.add(ticket);
        }
        return results;
    }
}
