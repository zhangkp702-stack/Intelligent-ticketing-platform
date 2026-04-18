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

import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区间位图工具
 */
public final class StationSegmentBitmapUtil {

    private StationSegmentBitmapUtil() {
    }

    /**
     * 为后续扩展 BitSet / byte[] 预留的接口
     */
    public interface SegmentBitmapOps<T> {

        T buildRequestMask(List<String> stations, String departure, String arrival);

        boolean hasConflict(T occupiedMask, T requestMask);

        List<String> decodeSegments(List<String> stations, T mask);
    }

    /**
     * 默认 long 位图实现（站点数 <= 64）
     */
    public static final class LongSegmentBitmapOps implements SegmentBitmapOps<Long> {

        @Override
        public Long buildRequestMask(List<String> stations, String departure, String arrival) {
            return StationSegmentBitmapUtil.buildRequestMask(stations, departure, arrival);
        }

        @Override
        public boolean hasConflict(Long occupiedMask, Long requestMask) {
            return occupiedMask != null && requestMask != null && (occupiedMask & requestMask) != 0;
        }

        @Override
        public List<String> decodeSegments(List<String> stations, Long mask) {
            return StationSegmentBitmapUtil.decodeSegments(stations, mask == null ? 0L : mask);
        }
    }

    public static long buildRequestMask(List<String> stations, String departure, String arrival) {
        Map<String, Integer> stationIndexMap = buildStationIndexMap(stations);
        Integer departureIndex = stationIndexMap.get(departure);
        Integer arrivalIndex = stationIndexMap.get(arrival);
        if (departureIndex == null || arrivalIndex == null || departureIndex >= arrivalIndex) {
            throw new ServiceException("出发站或到达站非法");
        }
        if (stations.size() > 65) {
            throw new ServiceException("当前 long 位图最多支持 64 个区段");
        }
        long mask = 0L;
        for (int i = departureIndex; i < arrivalIndex; i++) {
            mask |= (1L << i);
        }
        return mask;
    }

    public static boolean hasConflict(long occupyBitmap, long requestMask) {
        return (occupyBitmap & requestMask) != 0;
    }

    public static Map<String, Integer> buildStationIndexMap(List<String> stations) {
        Map<String, Integer> stationIndexMap = new LinkedHashMap<>();
        for (int i = 0; i < stations.size(); i++) {
            stationIndexMap.put(stations.get(i), i);
        }
        return stationIndexMap;
    }

    public static List<String> decodeSegments(List<String> stations, long mask) {
        List<String> segments = new ArrayList<>();
        if (stations.size() < 2) {
            return segments;
        }
        for (int i = 0; i < stations.size() - 1; i++) {
            if ((mask & (1L << i)) != 0) {
                segments.add(stations.get(i) + "->" + stations.get(i + 1));
            }
        }
        return segments;
    }
}
