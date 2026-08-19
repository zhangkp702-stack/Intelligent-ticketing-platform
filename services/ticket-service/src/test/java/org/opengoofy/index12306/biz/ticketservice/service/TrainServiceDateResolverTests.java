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
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseExecutionContext;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.boot.ApplicationArguments;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_STOPOVER_DETAIL;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;

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
        TrainMapper trainMapper = mock(TrainMapper.class);
        TrainStationMapper trainStationMapper = mock(TrainStationMapper.class);
        DistributedCache distributedCache = mock(DistributedCache.class);
        TrainServiceDateResolver resolver = new TrainServiceDateResolver(trainMapper, trainStationMapper, distributedCache);
        TrainDO train = new TrainDO();
        train.setId(1001L);
        train.setDepartureTime(dateOf(2026, 1, 1));
        TrainStationDO departureStation = new TrainStationDO();
        departureStation.setTrainId(1001L);
        departureStation.setSequence("2");
        departureStation.setDeparture("中间站");
        departureStation.setDepartureTime(dateOf(2026, 1, 2));
        when(trainMapper.selectList(null)).thenReturn(List.of(train));
        when(trainStationMapper.selectList(null)).thenReturn(List.of(departureStation));

        // 启动阶段预计算偏移并预热共享车次、经停站缓存。
        resolver.run(mock(ApplicationArguments.class));
        verify(distributedCache).put(eq(TRAIN_INFO + 1001L), eq(train), eq((long) ADVANCE_TICKET_DAY), eq(TimeUnit.DAYS));
        verify(distributedCache).put(eq(TRAIN_STATION_STOPOVER_DETAIL + 1001L),
                contains("中间站"), eq((long) ADVANCE_TICKET_DAY), eq(TimeUnit.DAYS));
        clearInvocations(trainMapper, trainStationMapper, distributedCache);

        // 正式请求只读取本机不可变偏移表，不再访问数据库或 Redis。
        Date serviceDate = resolver.resolve(1001L, dateOf(2026, 8, 15), "中间站");

        assertEquals(LocalDate.of(2026, 8, 14), serviceDate.toInstant().atZone(CHINA_ZONE_ID).toLocalDate());
        verifyNoInteractions(trainMapper, trainStationMapper, distributedCache);
    }

    /**
     * 未在启动快照中出现的车次站点必须快速失败，不能在购票热路径临时回源。
     */
    @Test
    void rejectsDepartureOffsetMissingFromWarmupSnapshot() {
        TrainMapper trainMapper = mock(TrainMapper.class);
        TrainStationMapper trainStationMapper = mock(TrainStationMapper.class);
        DistributedCache distributedCache = mock(DistributedCache.class);
        TrainServiceDateResolver resolver = new TrainServiceDateResolver(trainMapper, trainStationMapper, distributedCache);
        TrainDO train = new TrainDO();
        train.setId(1001L);
        train.setDepartureTime(dateOf(2026, 1, 1));
        TrainStationDO departureStation = new TrainStationDO();
        departureStation.setTrainId(1001L);
        departureStation.setSequence("1");
        departureStation.setDeparture("始发站");
        departureStation.setDepartureTime(dateOf(2026, 1, 1));
        when(trainMapper.selectList(null)).thenReturn(List.of(train));
        when(trainStationMapper.selectList(null)).thenReturn(List.of(departureStation));

        // 先形成固定启动快照，再验证未知站点不会触发运行时数据库补查。
        resolver.run(mock(ApplicationArguments.class));
        clearInvocations(trainMapper, trainStationMapper, distributedCache);

        assertThrows(ServiceException.class,
                () -> resolver.resolve(1001L, dateOf(2026, 8, 15), "未知站"));
        verifyNoInteractions(trainMapper, trainStationMapper, distributedCache);
    }

    /**
     * 购票执行上下文应从单次启动快照提供车次、有序站点和受影响区间。
     */
    @Test
    void preparesReusablePurchaseExecutionContextWithoutRuntimeIo() {
        TrainMapper trainMapper = mock(TrainMapper.class);
        TrainStationMapper trainStationMapper = mock(TrainStationMapper.class);
        DistributedCache distributedCache = mock(DistributedCache.class);
        TrainServiceDateResolver resolver = new TrainServiceDateResolver(trainMapper, trainStationMapper, distributedCache);
        TrainDO train = new TrainDO();
        train.setId(1001L);
        train.setStartStation("始发站");
        train.setEndStation("终点站");
        train.setDepartureTime(dateOf(2026, 1, 1));
        TrainStationDO first = station(1001L, "1", "始发站", dateOf(2026, 1, 1));
        TrainStationDO middle = station(1001L, "2", "中间站", dateOf(2026, 1, 1));
        TrainStationDO last = station(1001L, "3", "终点站", dateOf(2026, 1, 2));
        when(trainMapper.selectList(null)).thenReturn(List.of(train));
        when(trainStationMapper.selectList(null)).thenReturn(List.of(last, first, middle));
        resolver.run(mock(ApplicationArguments.class));
        clearInvocations(trainMapper, trainStationMapper, distributedCache);
        PurchaseTicketReqDTO request = new PurchaseTicketReqDTO();
        request.setTrainId("1001");
        request.setDeparture("始发站");
        request.setArrival("中间站");

        // 上下文构造只读取已发布的本地快照，并提前计算 Lua 需要的关联区间。
        PurchaseExecutionContext context = resolver.preparePurchaseExecutionContext(request);

        assertEquals(train, context.train());
        assertEquals(List.of("始发站", "中间站", "终点站"), context.stationNames());
        assertEquals(2, context.affectedRoutes().size());
        assertEquals("始发站", context.affectedRoutes().get(0).getStartStation());
        assertEquals("中间站", context.affectedRoutes().get(0).getEndStation());
        verifyNoInteractions(trainMapper, trainStationMapper, distributedCache);
    }

    /**
     * 构造测试所需的经停站静态数据。
     *
     * @param trainId 车次标识
     * @param sequence 站序
     * @param departure 站名
     * @param departureTime 发车时间
     * @return 经停站实体
     */
    private TrainStationDO station(Long trainId, String sequence, String departure, Date departureTime) {
        // 只填充预热和跨天偏移计算实际读取的字段。
        TrainStationDO station = new TrainStationDO();
        station.setTrainId(trainId);
        station.setSequence(sequence);
        station.setDeparture(departure);
        station.setDepartureTime(departureTime);
        return station;
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
