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

package org.opengoofy.index12306.biz.ticketservice.common.enums;

import java.util.Locale;

/**
 * 统一余票库存迁移模式。
 */
public enum TicketInventoryModeEnum {

    LEGACY,

    SHADOW,

    UNIFIED;

    /**
     * 将配置值转换为统一库存迁移模式。
     *
     * @param value 配置中的模式名称
     * @return 对应的库存迁移模式
     */
    public static TicketInventoryModeEnum from(String value) {
        // 配置缺失时继续执行既有库存链路，保证第一阶段不改变购票行为。
        if (value == null || value.isBlank()) {
            return LEGACY;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
