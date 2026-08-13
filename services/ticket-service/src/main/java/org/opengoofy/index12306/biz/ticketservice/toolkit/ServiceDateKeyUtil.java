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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 始发日期库存键工具。
 *
 * <p>所有运行库存的 Redis 键必须使用列车在始发站的开行日期，不能使用用户中途上车日期。</p>
 */
public final class ServiceDateKeyUtil {

    private static final ZoneId CHINA_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter KEY_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private ServiceDateKeyUtil() {
    }

    /**
     * 将始发日期转换为 Redis 键中的稳定日期片段。
     *
     * @param serviceDate 列车始发日期
     * @return yyyyMMdd 格式的键片段；历史数据未记录日期时返回空串
     */
    public static String format(Date serviceDate) {
        // 历史预订数据没有始发日期时继续读取旧键，保证异步释放可以平滑兼容。
        if (serviceDate == null) {
            return "";
        }
        return serviceDate.toInstant().atZone(CHINA_ZONE_ID).toLocalDate().format(KEY_DATE_FORMATTER);
    }

    /**
     * 构造带始发日期的业务键片段，并兼容未记录始发日期的历史键格式。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param segments 其余业务键片段
     * @return 以连接符拼接后的键片段
     */
    public static String buildKey(String trainId, Date serviceDate, String... segments) {
        List<String> keySegments = new ArrayList<>();
        // 历史预订没有始发日期时省略该片段，保证继续命中改造前 Redis 键。
        keySegments.add(trainId);
        if (serviceDate != null) {
            keySegments.add(format(serviceDate));
        }
        for (String each : segments) {
            keySegments.add(each);
        }
        return String.join("_", keySegments);
    }
}
