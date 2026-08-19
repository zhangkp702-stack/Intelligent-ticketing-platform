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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainSeatOccupancyMapper;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
     * 验证空候选不会生成非法批量更新语句。
     */
    @Test
    void emptySeatBatchShouldBeRejectedBeforeDatabaseAccess() {
        // 空集合直接返回，由上层重新选择候选座位。
        assertThat(seatService.tryLockSeat("1001", serviceDate(), "A", "B", List.of())).isFalse();
    }

    /**
     * 验证多座锁定通过一次静态座位批量查询和一次数据库位图条件更新完成。
     */
    @Test
    void tryLockSeatShouldBatchLoadSeatAndUseConditionalBitmapUpdate() {
        // 纯单元测试没有启动 MyBatis，先注册批量座位查询使用的实体元数据。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), SeatDO.class);
        Date serviceDate = serviceDate();
        SeatDO firstSeat = SeatDO.builder()
                .id(101L)
                .trainId(1001L)
                .carriageNumber("01")
                .seatNumber("01A")
                .seatType(1)
                .build();
        SeatDO secondSeat = SeatDO.builder()
                .id(102L)
                .trainId(1001L)
                .carriageNumber("01")
                .seatNumber("01B")
                .seatType(1)
                .build();
        TrainPurchaseTicketRespDTO firstTicket = ticket("01A");
        TrainPurchaseTicketRespDTO secondTicket = ticket("01B");

        // 车站路由生成区间位图，静态座位查询一次返回本批次全部座位。
        when(trainStationService.listTrainStationNameByTrainId("1001")).thenReturn(List.of("A", "B"));
        when(trainSeatOccupancyMapper.isServiceDateInventoryReady(1001L, serviceDate)).thenReturn(true);
        when(seatMapper.selectList(any())).thenReturn(List.of(firstSeat, secondSeat));
        when(trainSeatOccupancyMapper.tryLockSeatsByBitmap(
                eq(1001L), eq(serviceDate), eq(List.of(101L, 102L)), anyLong())).thenReturn(2);

        // Redis owner 已在上层取得，数据库只发送一条批量 CAS 作为最终确认。
        assertThat(seatService.tryLockSeat(
                "1001", serviceDate, "A", "B", List.of(firstTicket, secondTicket))).isTrue();
        verify(seatMapper, times(1)).selectList(any());
        verify(trainSeatOccupancyMapper, times(1))
                .tryLockSeatsByBitmap(eq(1001L), eq(serviceDate), eq(List.of(101L, 102L)), anyLong());
    }

    /**
     * 验证批量 CAS 只更新部分座位时必须中断流程，由外层事务整体回滚本批次。
     */
    @Test
    void partiallyLockedBatchShouldRequireTransactionRollback() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), SeatDO.class);
        Date serviceDate = serviceDate();
        SeatDO firstSeat = SeatDO.builder()
                .id(101L)
                .trainId(1001L)
                .carriageNumber("01")
                .seatNumber("01A")
                .seatType(1)
                .build();
        SeatDO secondSeat = SeatDO.builder()
                .id(102L)
                .trainId(1001L)
                .carriageNumber("01")
                .seatNumber("01B")
                .seatType(1)
                .build();

        // 模拟两个候选座位中只有一个通过数据库条件更新，禁止当前事务继续换座。
        when(trainStationService.listTrainStationNameByTrainId("1001")).thenReturn(List.of("A", "B"));
        when(trainSeatOccupancyMapper.isServiceDateInventoryReady(1001L, serviceDate)).thenReturn(true);
        when(seatMapper.selectList(any())).thenReturn(List.of(firstSeat, secondSeat));
        when(trainSeatOccupancyMapper.tryLockSeatsByBitmap(
                eq(1001L), eq(serviceDate), eq(List.of(101L, 102L)), anyLong())).thenReturn(1);

        assertThatThrownBy(() -> seatService.tryLockSeat(
                "1001", serviceDate, "A", "B", List.of(ticket("01A"), ticket("01B"))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("部分冲突");
    }

    /**
     * 构造同一车厢内的测试座位请求。
     *
     * @param seatNumber 座位号
     * @return 固定席别的锁座请求
     */
    private TrainPurchaseTicketRespDTO ticket(String seatNumber) {
        // 测试只关注批量座位主键映射，不需要乘客和票价字段。
        TrainPurchaseTicketRespDTO ticket = new TrainPurchaseTicketRespDTO();
        ticket.setCarriageNumber("01");
        ticket.setSeatNumber(seatNumber);
        ticket.setSeatType(1);
        return ticket;
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
