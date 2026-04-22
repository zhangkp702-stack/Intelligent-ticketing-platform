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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 高铁二等座购票组件
 * 完整版布局选座：优先精确选座，其次同排组合，再按窗口/过道偏好与邻近性回退。
 */
@Component
@RequiredArgsConstructor
public class TrainSecondClassPurchaseTicketHandler extends AbstractTrainPurchaseTicketTemplate {

    private final SeatService seatService;

    /**
     * 返回当前处理器对应的策略标识：高铁二等座。
     */
    @Override
    public String mark() {
        return VehicleTypeEnum.HIGH_SPEED_RAIN.getName() + VehicleSeatTypeEnum.SECOND_CLASS.getName();
    }

    /**
     * 二等座选座入口，加载二等座座位布局后进入布局选座逻辑。
     */
    @Override
    protected List<TrainPurchaseTicketRespDTO> selectSeats(SelectSeatDTO requestParam) {
        return selectByLayout(requestParam, SeatLayoutBitmapUtil.profileBySeatType(VehicleSeatTypeEnum.SECOND_CLASS.getCode()));
    }

    /**
     * 在指定车厢范围内为乘车人选择二等座座位。
     * 优先使用上游指定的候选车厢；没有指定时，会随机遍历可用车厢。
     */
    private List<TrainPurchaseTicketRespDTO> selectByLayout(SelectSeatDTO requestParam, SeatLayoutBitmapUtil.LayoutProfile profile) {
        String trainId = requestParam.getRequestParam().getTrainId();
        String departure = requestParam.getRequestParam().getDeparture();
        String arrival = requestParam.getRequestParam().getArrival();
        // 获取乘车人选择座位详情
        List<PurchaseTicketPassengerDetailDTO> passengers = requestParam.getPassengerSeatDetails();
        List<String> carriageNumbers;
        if (StrUtil.isNotBlank(requestParam.getPreferredCarriageNumber())) {
            carriageNumbers = List.of(requestParam.getPreferredCarriageNumber());
        } else {
            carriageNumbers = new ArrayList<>(seatService.listUsableCarriageNumber(trainId, requestParam.getSeatType(), departure, arrival));
            Collections.shuffle(carriageNumbers, ThreadLocalRandom.current());
        }
        // 去重车厢号
        Set<String> excludedSeatNumbers = CollUtil.isEmpty(requestParam.getExcludeSeatNumbers())
                ? Set.of()
                : new LinkedHashSet<>(requestParam.getExcludeSeatNumbers());
        for (String carriageNumber : carriageNumbers) {
            // 获取当前车厢可用座位，使用当前站台区间的位图和座位进行与运算
            List<String> availableSeats = new ArrayList<>(seatService.listAvailableSeat(trainId, carriageNumber, requestParam.getSeatType(), departure, arrival).stream()
                    .filter(each -> !isExcludedSeat(excludedSeatNumbers, carriageNumber, each))
                    .toList());
            // 按扫描偏移量旋转可用座位列表，避免所有请求都从同一批座位开始尝试
            rotateAvailableSeats(availableSeats, requestParam.getSeatScanOffset());
            // 如果查询出来的座位数量不足，跳过当前车厢
            if (availableSeats.size() < passengers.size()) {
                continue;
            }
            // 开始选座，根据用户选座偏好和二等座布局生成候选座位组合
            List<List<String>> candidates = buildSeatCandidates(availableSeats, requestParam.getRequestParam().getChooseSeats(), passengers.size(), profile);
            for (List<String> candidate : candidates) {
                if (candidate.size() == passengers.size()) {
                    return buildResult(passengers, carriageNumber, candidate);
                }
            }
        }
        throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
    }

    /**
     * 判断某个座位是否已经在本轮车厢重试中被排除。
     */
    private boolean isExcludedSeat(Set<String> excludedSeatNumbers, String carriageNumber, String seatNumber) {
        return excludedSeatNumbers.contains(seatNumber)
                || excludedSeatNumbers.contains(carriageNumber + "#" + seatNumber);
    }

    /**
     * 按扫描偏移量旋转可用座位列表，避免所有请求都从同一批座位开始尝试。
     */
    private void rotateAvailableSeats(List<String> availableSeats, Integer offset) {
        if (CollUtil.isEmpty(availableSeats) || offset == null || availableSeats.size() <= 1) {
            return;
        }
        int actualOffset = Math.floorMod(offset, availableSeats.size());
        if (actualOffset > 0) {
            Collections.rotate(availableSeats, -actualOffset);
        }
    }

    /**
     * 根据可用座位、用户选座偏好和二等座布局生成候选座位组合。
     * 候选顺序依次为：精确选座、选座加补位、相邻座位、同排座位、全局兜底座位。
     */
    private List<List<String>> buildSeatCandidates(List<String> availableSeats, List<String> chooseSeats, int seatCount, SeatLayoutBitmapUtil.LayoutProfile profile) {
        Set<String> distinctAvailableSeats = new LinkedHashSet<>(availableSeats);
        List<List<String>> result = new ArrayList<>();
        List<String> preferredColumnSeats = pickPreferredColumnSeats(distinctAvailableSeats, chooseSeats, seatCount, profile);
        if (preferredColumnSeats.size() == seatCount) {
            result.add(preferredColumnSeats);
        }
        BitSet availableMask = SeatLayoutBitmapUtil.buildAvailableMask(new ArrayList<>(distinctAvailableSeats), profile);
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

    /**
     * 从用户选择的座位列偏好中挑出当前车厢仍可用的具体座位。
     */
    private List<String> pickPreferredColumnSeats(Set<String> availableSeats, List<String> chooseSeats, int seatCount, SeatLayoutBitmapUtil.LayoutProfile profile) {
        List<Character> preferredColumns = buildPreferredColumns(chooseSeats, profile);
        if (CollUtil.isEmpty(preferredColumns)) {
            return List.of();
        }
        List<SeatLayoutBitmapUtil.SeatCoordinate> sortedCoordinates = availableSeats.stream()
                .map(each -> SeatLayoutBitmapUtil.parseSeatCoordinate(each, profile))
                .sorted(candidateComparator(profile))
                .toList();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Character preferredColumn : preferredColumns) {
            for (SeatLayoutBitmapUtil.SeatCoordinate coordinate : sortedCoordinates) {
                String seatNumber = SeatLayoutBitmapUtil.toSeatNumber(coordinate, profile);
                if (!result.contains(seatNumber) && isPreferredColumn(seatNumber, preferredColumn)) {
                    result.add(seatNumber);
                    break;
                }
            }
            if (result.size() >= seatCount) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 保留用户座位列偏好对应的可用座位，并按系统座位偏好补齐剩余座位。
     */
    private List<String> mergeChooseAndFallback(Set<String> availableSeats, List<String> chooseSeats,
                                                List<SeatLayoutBitmapUtil.SeatCoordinate> sortedCoordinates,
                                                int seatCount, SeatLayoutBitmapUtil.LayoutProfile profile) {
        LinkedHashSet<String> result = new LinkedHashSet<>(pickPreferredColumnSeats(availableSeats, chooseSeats, seatCount, profile));
        for (SeatLayoutBitmapUtil.SeatCoordinate coordinate : sortedCoordinates) {
            if (result.size() >= seatCount) {
                break;
            }
            result.add(SeatLayoutBitmapUtil.toSeatNumber(coordinate, profile));
        }
        return new ArrayList<>(result).subList(0, Math.min(result.size(), seatCount));
    }

    /**
     * 将用户输入的 A/B/C/D/F 座位列偏好转换为标准列集合。
     */
    private List<Character> buildPreferredColumns(List<String> chooseSeats, SeatLayoutBitmapUtil.LayoutProfile profile) {
        if (CollUtil.isEmpty(chooseSeats)) {
            return List.of();
        }
        return chooseSeats.stream()
                .filter(StrUtil::isNotBlank)
                .map(each -> each.trim().toUpperCase(Locale.ROOT))
                .filter(each -> each.length() == 1)
                .map(each -> each.charAt(0))
                .filter(each -> profile.seatColumnMap().containsKey(each))
                .toList();
    }

    /**
     * 判断具体座位号是否命中用户指定的座位列偏好。
     */
    private boolean isPreferredColumn(String seatNumber, Character preferredColumn) {
        return StrUtil.isNotBlank(seatNumber)
                && preferredColumn != null
                && Character.toUpperCase(seatNumber.charAt(seatNumber.length() - 1)) == preferredColumn;
    }

    /**
     * 构造同一排内的候选座位组合，用于相邻座位无法满足时的同排兜底。
     */
    private List<List<SeatLayoutBitmapUtil.SeatCoordinate>> buildSameRowCombos(List<SeatLayoutBitmapUtil.SeatCoordinate> sortedCoordinates, int seatCount) {
        return sortedCoordinates.stream()
                .collect(Collectors.groupingBy(SeatLayoutBitmapUtil.SeatCoordinate::rowIndex))
                .values().stream()
                .filter(each -> each.size() >= seatCount)
                .map(each -> each.stream().limit(seatCount).toList())
                .toList();
    }

    /**
     * 从全车厢可用座位排序结果中滑动截取候选组合，作为最后兜底方案。
     */
    private List<List<SeatLayoutBitmapUtil.SeatCoordinate>> buildGlobalCombos(List<SeatLayoutBitmapUtil.SeatCoordinate> sortedCoordinates, int seatCount) {
        List<List<SeatLayoutBitmapUtil.SeatCoordinate>> result = new ArrayList<>();
        for (int i = 0; i <= sortedCoordinates.size() - seatCount; i++) {
            result.add(sortedCoordinates.subList(i, i + seatCount));
        }
        return result;
    }

    /**
     * 构造单座排序器：优先窗口位，其次过道位，并倾向靠前排座位。
     */
    private Comparator<SeatLayoutBitmapUtil.SeatCoordinate> candidateComparator(SeatLayoutBitmapUtil.LayoutProfile profile) {
        return Comparator.comparingInt((SeatLayoutBitmapUtil.SeatCoordinate each) -> seatPreferenceScore(each, profile)).reversed()
                .thenComparingInt(SeatLayoutBitmapUtil.SeatCoordinate::rowIndex)
                .thenComparingInt(SeatLayoutBitmapUtil.SeatCoordinate::colIndex);
    }

    /**
     * 计算一组座位的综合偏好分数，用于候选组合排序。
     */
    private int score(List<SeatLayoutBitmapUtil.SeatCoordinate> coordinates, SeatLayoutBitmapUtil.LayoutProfile profile) {
        return coordinates.stream().mapToInt(each -> seatPreferenceScore(each, profile)).sum();
    }

    /**
     * 计算单个座位偏好分数：窗口位加分最多，过道位次之，排号越靠前分数越高。
     */
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

    /**
     * 将最终座位号按乘车人顺序组装为购票结果。
     */
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
