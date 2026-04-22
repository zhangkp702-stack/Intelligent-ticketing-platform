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

package org.opengoofy.index12306.biz.ticketservice.toolkit;

import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 座位布局位图工具
 */
public final class SeatLayoutBitmapUtil {

    private SeatLayoutBitmapUtil() {
    }

    public record SeatCoordinate(int rowIndex, int colIndex) {
    }

    public record LayoutProfile(int rowCount, int colCount, Map<Character, Integer> seatColumnMap,
                                long windowMask, long aisleMask) {
    }

    public static LayoutProfile profileBySeatType(Integer seatType) {
        if (seatType == null) {
            return new LayoutProfile(18, 5, secondClassColumnMap(), bitMask(0, 4), bitMask(2, 3));
        }
        if (seatType.equals(VehicleSeatTypeEnum.BUSINESS_CLASS.getCode())) {
            return new LayoutProfile(2, 3, businessClassColumnMap(), bitMask(0, 2), bitMask(1, 1));
        }
        if (seatType.equals(VehicleSeatTypeEnum.FIRST_CLASS.getCode())) {
            return new LayoutProfile(7, 4, firstClassColumnMap(), bitMask(0, 3), bitMask(1, 2));
        }
        return new LayoutProfile(18, 5, secondClassColumnMap(), bitMask(0, 4), bitMask(2, 3));
    }

    public static List<List<SeatCoordinate>> buildAdjacentCombos(BitSet availableMask, int seatCount, LayoutProfile profile) {
        List<List<SeatCoordinate>> combinations = new ArrayList<>();
        for (int row = 0; row < profile.rowCount(); row++) {
            for (int start = 0; start <= profile.colCount() - seatCount; start++) {
                if (isAdjacentAvailable(availableMask, row, start, seatCount, profile)) {
                    List<SeatCoordinate> coordinates = new ArrayList<>();
                    for (int c = 0; c < seatCount; c++) {
                        coordinates.add(new SeatCoordinate(row, start + c));
                    }
                    combinations.add(coordinates);
                }
            }
        }
        return combinations;
    }

    public static List<SeatCoordinate> chooseNearestOrRandom(BitSet availableMask, int seatCount, LayoutProfile profile) {
        List<SeatCoordinate> all = decodeAvailableSeats(availableMask, profile);
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        return all.stream()
                .sorted(Comparator.comparingInt(SeatCoordinate::rowIndex).thenComparingInt(SeatCoordinate::colIndex))
                .limit(seatCount)
                .collect(Collectors.toList());
    }

    public static BitSet buildAvailableMask(List<String> seatNumbers, LayoutProfile profile) {
        BitSet mask = new BitSet(profile.rowCount() * profile.colCount());
        for (String seatNo : seatNumbers) {
            SeatCoordinate coordinate = parseSeatCoordinate(seatNo, profile);
            int index = coordinate.rowIndex() * profile.colCount() + coordinate.colIndex();
            mask.set(index);
        }
        return mask;
    }

    public static List<SeatCoordinate> decodeAvailableSeats(BitSet availableMask, LayoutProfile profile) {
        List<SeatCoordinate> result = new ArrayList<>();
        int maxSeatCount = profile.rowCount() * profile.colCount();
        for (int i = availableMask.nextSetBit(0); i >= 0 && i < maxSeatCount; i = availableMask.nextSetBit(i + 1)) {
            // 检查第i位是不是1，如果是1，说明这个座位是可用的
            result.add(new SeatCoordinate(i / profile.colCount(), i % profile.colCount()));
        }
        return result;
    }

    private static boolean isAdjacentAvailable(BitSet availableMask, int row, int start, int seatCount, LayoutProfile profile) {
        int rowOffset = row * profile.colCount();
        for (int i = 0; i < seatCount; i++) {
            if (!availableMask.get(rowOffset + start + i)) {
                return false;
            }
        }
        return true;
    }

    public static SeatCoordinate parseSeatCoordinate(String seatNo, LayoutProfile profile) {
        int row = Integer.parseInt(seatNo.substring(0, seatNo.length() - 1)) - 1;
        char colCode = seatNo.charAt(seatNo.length() - 1);
        Integer col = profile.seatColumnMap().get(colCode);
        return new SeatCoordinate(row, col == null ? 0 : col);
    }

    public static String toSeatNumber(SeatCoordinate coordinate, LayoutProfile profile) {
        int rowNum = coordinate.rowIndex() + 1;
        char colCode = profile.seatColumnMap().entrySet().stream()
                .filter(each -> each.getValue().equals(coordinate.colIndex()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse('A');
        return String.format("%02d%s", rowNum, colCode);
    }

    public static List<String> toSeatNumbers(List<SeatCoordinate> coordinates, LayoutProfile profile) {
        return coordinates.stream().map(each -> toSeatNumber(each, profile)).toList();
    }

    private static Map<Character, Integer> firstClassColumnMap() {
        Map<Character, Integer> map = new LinkedHashMap<>();
        map.put('A', 0);
        map.put('C', 1);
        map.put('D', 2);
        map.put('F', 3);
        return map;
    }

    private static Map<Character, Integer> secondClassColumnMap() {
        Map<Character, Integer> map = new LinkedHashMap<>();
        map.put('A', 0);
        map.put('B', 1);
        map.put('C', 2);
        map.put('D', 3);
        map.put('F', 4);
        return map;
    }

    private static Map<Character, Integer> businessClassColumnMap() {
        Map<Character, Integer> map = new LinkedHashMap<>();
        map.put('A', 0);
        map.put('C', 1);
        map.put('F', 2);
        return map;
    }

    private static long bitMask(int... indexes) {
        return Arrays.stream(indexes).mapToLong(i -> 1L << i).reduce(0L, (left, right) -> left | right);
    }
}
