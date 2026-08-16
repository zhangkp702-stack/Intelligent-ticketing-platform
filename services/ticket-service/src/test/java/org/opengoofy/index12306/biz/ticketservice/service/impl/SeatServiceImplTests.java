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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainSeatOccupancyMapper;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证日期运行库存只做就绪检查，不在购票热路径执行批量初始化。
 */
@ExtendWith(MockitoExtension.class)
class SeatServiceImplTests {

    @Mock
    private SeatMapper seatMapper;
    @Mock
    private TrainSeatOccupancyMapper trainSeatOccupancyMapper;
    @Mock
    private TrainStationService trainStationService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private DistributedCache distributedCache;
    @InjectMocks
    private SeatServiceImpl seatService;

    /**
     * 验证完整库存通过一次数据库检查后会在本实例复用成功状态。
     */
    @Test
    void readyInventoryShouldBeValidatedOnceAndCached() {
        Date serviceDate = serviceDate();
        when(trainSeatOccupancyMapper.isServiceDateInventoryReady(1001L, serviceDate)).thenReturn(true);

        // 同一车次日期连续校验时，第二次不应再次访问数据库。
        assertThatCode(() -> seatService.validateServiceDateInventoryReady("1001", serviceDate)).doesNotThrowAnyException();
        assertThatCode(() -> seatService.validateServiceDateInventoryReady("1001", serviceDate)).doesNotThrowAnyException();

        verify(trainSeatOccupancyMapper, times(1)).isServiceDateInventoryReady(1001L, serviceDate);
    }

    /**
     * 验证未就绪状态快速失败且不会缓存，数据补齐后可以立即重新检查。
     */
    @Test
    void missingInventoryShouldFailWithoutCachingNegativeResult() {
        Date serviceDate = serviceDate();
        when(trainSeatOccupancyMapper.isServiceDateInventoryReady(1001L, serviceDate))
                .thenReturn(false)
                .thenReturn(true);

        // 第一次缺失必须失败，第二次模拟预热完成后应直接恢复。
        assertThatThrownBy(() -> seatService.validateServiceDateInventoryReady("1001", serviceDate))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("库存未就绪");
        assertThatCode(() -> seatService.validateServiceDateInventoryReady("1001", serviceDate)).doesNotThrowAnyException();

        verify(trainSeatOccupancyMapper, times(2)).isServiceDateInventoryReady(1001L, serviceDate);
    }

    /**
     * 创建不受系统默认时区影响的固定始发日期。
     *
     * @return 测试使用的始发日期
     */
    private Date serviceDate() {
        // 固定中国时区，保证缓存键和 Mockito 参数在不同执行环境保持一致。
        return Date.from(LocalDate.of(2026, 8, 16)
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant());
    }
}
