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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select;

/**
 * Redis 乐观占位统计样本类型。
 */
public enum SeatSelectionSampleType {

    /** 常态乐观通道的占位样本。 */
    NORMAL("normal"),

    /** 单通道探测或渐进恢复阶段的乐观占位样本。 */
    PROBE("probe");

    private final String value;

    SeatSelectionSampleType(String value) {
        this.value = value;
    }

    /**
     * 返回 Redis 键使用的低基数样本名称。
     *
     * @return 样本名称
     */
    public String value() {
        // Redis 键只使用固定枚举值，避免用户输入进入键空间。
        return value;
    }
}
