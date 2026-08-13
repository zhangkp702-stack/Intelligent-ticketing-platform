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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 根据用户上车日期和基础时刻表计算列车始发日期。
 *
 * <p>运行库存必须使用始发日期，而不是中间站乘客的上车日期，才能把同一车次不同开行日隔离开。</p>
 */
@Component
@RequiredArgsConstructor
public class TrainServiceDateResolver {

    private static final ZoneId CHINA_ZONE_ID = ZoneId.of("Asia/Shanghai");

    private final TrainStationMapper trainStationMapper;

    /**
     * 计算一次购票对应列车从始发站出发的日期。
     *
     * @param train 列车基础时刻信息
     * @param ridingDate 用户从指定出发站上车的日期
     * @param departure 用户选择的出发站
     * @return 与运行库存绑定的列车始发日期
     */
    public Date resolve(TrainDO train, Date ridingDate, String departure) {
        // 请求日期、列车基础始发时刻和出发站是推导运行日期的必要输入，缺失时不能继续使用默认日期。
        if (train == null || train.getId() == null || train.getDepartureTime() == null || ridingDate == null) {
            throw new ServiceException("计算列车始发日期失败");
        }

        // 从基础时刻表读取用户上车站的计划发车时刻，用于计算它相对始发站跨越的自然日数。
        TrainStationDO departureStation = trainStationMapper.selectOne(Wrappers.lambdaQuery(TrainStationDO.class)
                .eq(TrainStationDO::getTrainId, train.getId())
                .eq(TrainStationDO::getDeparture, departure));
        if (departureStation == null || departureStation.getDepartureTime() == null) {
            throw new ServiceException("列车出发站时刻不存在");
        }

        // 基础时刻表的日期只表达跨天偏移；将该偏移从乘客上车日期中扣除后得到真实始发日期。
        long departureDayOffset = ChronoUnit.DAYS.between(toLocalDate(train.getDepartureTime()),
                toLocalDate(departureStation.getDepartureTime()));
        if (departureDayOffset < 0) {
            throw new ServiceException("列车出发站时刻异常");
        }
        LocalDate serviceDate = toLocalDate(ridingDate).minusDays(departureDayOffset);
        return Date.from(serviceDate.atStartOfDay(CHINA_ZONE_ID).toInstant());
    }

    /**
     * 将旧版时刻表的 Date 按中国时区转换为自然日。
     *
     * @param value 数据库存储的日期时间
     * @return 对应的中国时区自然日
     */
    private LocalDate toLocalDate(Date value) {
        // 显式指定业务时区，避免部署主机时区变化导致库存日期被错误分片。
        return value.toInstant().atZone(CHINA_ZONE_ID).toLocalDate();
    }
}
