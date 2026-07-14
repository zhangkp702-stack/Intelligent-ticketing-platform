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

package org.opengoofy.index12306.biz.ticketservice.service.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.framework.starter.cache.toolkit.CacheUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Local cache for ticket availability display.
 * Purchase, token bucket and seat locking logic must still use Redis/DB.
 */
@Component
public class TicketAvailabilityLocalCache {

    private final Cache<String, Map<String, String>> cache;

    public TicketAvailabilityLocalCache(@Value("${ticket.availability.local-cache.ttl-seconds:30}") long ttlSeconds,
                                        @Value("${ticket.availability.local-cache.maximum-size:100000}") long maximumSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }

    public Integer getSeatQuantity(String trainId, String departure, String arrival, String seatType) {
        return Optional.ofNullable(cache.getIfPresent(buildKey(trainId, departure, arrival)))
                .map(each -> each.get(seatType))
                .map(Integer::parseInt)
                .orElse(null);
    }

    public void putSeatQuantity(String trainId, String departure, String arrival, String seatType, Object quantity) {
        if (quantity == null) {
            return;
        }
        String cacheKey = buildKey(trainId, departure, arrival);
        Map<String, String> seatQuantityMap = Optional.ofNullable(cache.getIfPresent(cacheKey))
                .map(LinkedHashMap::new)
                .orElseGet(LinkedHashMap::new);
        seatQuantityMap.put(seatType, String.valueOf(quantity));
        cache.put(cacheKey, seatQuantityMap);
    }

    public void putRemainingTickets(String trainId, String departure, String arrival, Map<String, String> remainingTickets) {
        if (remainingTickets == null || remainingTickets.isEmpty()) {
            return;
        }
        cache.put(buildKey(trainId, departure, arrival), new LinkedHashMap<>(remainingTickets));
    }

    public void invalidate(String trainId, String departure, String arrival) {
        cache.invalidate(buildKey(trainId, departure, arrival));
    }

    public void invalidateRoutes(String trainId, List<RouteDTO> routeDTOList) {
        if (routeDTOList == null) {
            return;
        }
        routeDTOList.forEach(each -> invalidate(trainId, each.getStartStation(), each.getEndStation()));
    }

    private String buildKey(String trainId, String departure, String arrival) {
        return CacheUtil.buildKey(trainId, departure, arrival);
    }
}
