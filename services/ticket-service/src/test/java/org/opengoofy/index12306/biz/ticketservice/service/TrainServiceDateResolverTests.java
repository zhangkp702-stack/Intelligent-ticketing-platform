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

package org.opengoofy.index12306.biz.ticketservice.service;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证跨天列车按始发日期而不是中间站上车日期隔离库存。
 */
class TrainServiceDateResolverTests {

    private static final ZoneId CHINA_ZONE_ID = ZoneId.of("Asia/Shanghai");

    /**
     * 中间站次日发车时，应将乘客上车日回退为列车始发日。
     */
    @Test
    void resolvesOriginServiceDateForNextDayDepartureStation() {
        TrainStationMapper trainStationMapper = mock(TrainStationMapper.class);
        TrainServiceDateResolver resolver = new TrainServiceDateResolver(trainStationMapper);
        TrainDO train = new TrainDO();
        train.setId(1001L);
        train.setDepartureTime(dateOf(2026, 1, 1));
        TrainStationDO departureStation = new TrainStationDO();
        departureStation.setDepartureTime(dateOf(2026, 1, 2));
        when(trainStationMapper.selectOne(any())).thenReturn(departureStation);

        Date serviceDate = resolver.resolve(train, dateOf(2026, 8, 15), "中间站");

        assertEquals(LocalDate.of(2026, 8, 14), serviceDate.toInstant().atZone(CHINA_ZONE_ID).toLocalDate());
    }

    /**
     * 按中国时区构造日期，避免测试主机时区影响跨天断言。
     *
     * @param year 年
     * @param month 月
     * @param day 日
     * @return 对应自然日零点的 Date
     */
    private Date dateOf(int year, int month, int day) {
        // 生产代码同样使用中国时区，测试使用一致的业务时区验证日期回退。
        return Date.from(LocalDate.of(year, month, day).atStartOfDay(CHINA_ZONE_ID).toInstant());
    }
}
