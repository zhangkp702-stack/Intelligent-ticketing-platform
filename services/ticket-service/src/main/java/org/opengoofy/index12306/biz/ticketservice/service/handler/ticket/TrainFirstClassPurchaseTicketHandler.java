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
import cn.hutool.core.util.StrUtil;
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
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 高铁一等座购票组件
 * 完整版布局选座：优先精确选座，其次同排组合，再按窗口/过道偏好与邻近性回退。
 */
@Component
@RequiredArgsConstructor
public class TrainFirstClassPurchaseTicketHandler extends AbstractTrainPurchaseTicketTemplate {

    private final SeatService seatService;

    @Override
    public String mark() {
        return VehicleTypeEnum.HIGH_SPEED_RAIN.getName() + VehicleSeatTypeEnum.FIRST_CLASS.getName();
    }

    @Override
    protected List<TrainPurchaseTicketRespDTO> selectSeats(SelectSeatDTO requestParam) {
        return selectByLayout(requestParam, SeatLayoutBitmapUtil.profileBySeatType(VehicleSeatTypeEnum.FIRST_CLASS.getCode()));
    }

    private List<TrainPurchaseTicketRespDTO> selectByLayout(SelectSeatDTO requestParam, SeatLayoutBitmapUtil.LayoutProfile profile) {
        String trainId = requestParam.getRequestParam().getTrainId();
        String departure = requestParam.getRequestParam().getDeparture();
        String arrival = requestParam.getRequestParam().getArrival();
        // 获取乘车人集合
        List<PurchaseTicketPassengerDetailDTO> passengers = requestParam.getPassengerSeatDetails();
        // 查询列车有余票的车厢号集合
        List<String> carriageNumbers;
        if (StrUtil.isNotBlank(requestParam.getPreferredCarriageNumber())) {
            carriageNumbers = List.of(requestParam.getPreferredCarriageNumber());
        } else {
            carriageNumbers = new ArrayList<>(seatService.listUsableCarriageNumber(trainId, requestParam.getSeatType(), departure, arrival));
            Collections.shuffle(carriageNumbers, ThreadLocalRandom.current());
        }
        // 获取用户选座意愿
        Set<String> excludedSeatNumbers = CollUtil.isEmpty(requestParam.getExcludeSeatNumbers())
                ? Set.of()
                : new LinkedHashSet<>(requestParam.getExcludeSeatNumbers());
        // 开始逐车厢开始选座
        for (String carriageNumber : carriageNumbers) {
            // 查找所有可用座位
            List<String> availableSeats = new ArrayList<>(seatService.listAvailableSeat(trainId, carriageNumber, requestParam.getSeatType(), departure, arrival).stream()
                    .filter(each -> !isExcludedSeat(excludedSeatNumbers, carriageNumber, each))
                    .toList());
            rotateAvailableSeats(availableSeats, requestParam.getSeatScanOffset());
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

    // 对每一个车厢都进行选座组合的构建
    private boolean isExcludedSeat(Set<String> excludedSeatNumbers, String carriageNumber, String seatNumber) {
        return excludedSeatNumbers.contains(seatNumber)
                || excludedSeatNumbers.contains(carriageNumber + "#" + seatNumber);
    }

    private void rotateAvailableSeats(List<String> availableSeats, Integer offset) {
        if (CollUtil.isEmpty(availableSeats) || offset == null || availableSeats.size() <= 1) {
            return;
        }
        int actualOffset = Math.floorMod(offset, availableSeats.size());
        if (actualOffset > 0) {
            Collections.rotate(availableSeats, -actualOffset);
        }
    }

    private static final int MAX_ADJACENT_CANDIDATES = 5;
    private static final int MAX_SAME_ROW_CANDIDATES = 5;
    private static final int MAX_GLOBAL_CANDIDATES = 3;

    private List<List<String>> buildSeatCandidates(List<String> availableSeats, List<String> chooseSeats, int seatCount, SeatLayoutBitmapUtil.LayoutProfile profile) {
        Set<String> distinctAvailableSeats = new LinkedHashSet<>(availableSeats);
        List<List<String>> result = new ArrayList<>();
        Set<String> dedupKeys = new LinkedHashSet<>();

        // 1. 用户精确选择，最多 1 个
        List<String> exactChooseSeats = pickExactChooseSeats(distinctAvailableSeats, chooseSeats, seatCount);
        addCandidate(result, dedupKeys, exactChooseSeats, seatCount);

        // 2. 直接解析可用座位坐标，避免先 buildMask 再 decode
        List<SeatLayoutBitmapUtil.SeatCoordinate> availableCoordinates = distinctAvailableSeats.stream()
                .map(each -> SeatLayoutBitmapUtil.parseSeatCoordinate(each, profile))
                .sorted(candidateComparator(profile))
                .toList();

        // 3. 用户选择 + 系统补位，最多 1 个
        List<String> choosePlusFallback = mergeChooseAndFallback(
                distinctAvailableSeats, chooseSeats, availableCoordinates, seatCount, profile
        );
        addCandidate(result, dedupKeys, choosePlusFallback, seatCount);

        // 4. 相邻组合：只保留前 5 个
        BitSet availableMask = SeatLayoutBitmapUtil.buildAvailableMask(new ArrayList<>(distinctAvailableSeats), profile);
        SeatLayoutBitmapUtil.buildAdjacentCombos(availableMask, seatCount, profile).stream()
                .sorted(Comparator.comparingInt((List<SeatLayoutBitmapUtil.SeatCoordinate> each) -> score(each, profile)).reversed()
                        .thenComparingInt(each -> each.get(0).rowIndex())
                        .thenComparingInt(each -> each.get(0).colIndex()))
                .limit(MAX_ADJACENT_CANDIDATES)
                .map(each -> SeatLayoutBitmapUtil.toSeatNumbers(each, profile))
                .forEach(each -> addCandidate(result, dedupKeys, each, seatCount));

        // 5. 同排组合：只保留前 5 个
        buildSameRowCombos(availableCoordinates, seatCount).stream()
                .limit(MAX_SAME_ROW_CANDIDATES)
                .map(each -> SeatLayoutBitmapUtil.toSeatNumbers(each, profile))
                .forEach(each -> addCandidate(result, dedupKeys, each, seatCount));

        // 6. 全局组合：只保留前 3 个
        buildGlobalCombos(availableCoordinates, seatCount).stream()
                .limit(MAX_GLOBAL_CANDIDATES)
                .map(each -> SeatLayoutBitmapUtil.toSeatNumbers(each, profile))
                .forEach(each -> addCandidate(result, dedupKeys, each, seatCount));

        return result;
    }

    private void addCandidate(List<List<String>> result, Set<String> dedupKeys, List<String> candidate, int seatCount) {
        if (candidate == null || candidate.size() != seatCount) {
            return;
        }
        List<String> normalized = new ArrayList<>(candidate);
        String dedupKey = String.join(",", normalized);
        if (dedupKeys.add(dedupKey)) {
            result.add(normalized);
        }
    }

    // 用户精确选座
    private List<String> pickExactChooseSeats(Set<String> availableSeats, List<String> chooseSeats, int seatCount) {
        if (CollUtil.isEmpty(chooseSeats)) {
            return List.of();
        }
        return chooseSeats.stream().filter(availableSeats::contains).distinct().limit(seatCount).toList();
    }

    // 混合选座
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
