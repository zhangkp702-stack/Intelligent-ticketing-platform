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
 * 阶段四 Redis 冲突统计窗口，统一阶段三约定的统计口径。
 */
public enum SeatSelectionWindow {

    /** 常态流量快速窗口：五个百毫秒桶，共五百毫秒。 */
    NORMAL_FAST(SeatSelectionSampleType.NORMAL, 5),

    /** 常态流量稳定窗口：二十个百毫秒桶，共两秒。 */
    NORMAL_STABLE(SeatSelectionSampleType.NORMAL, 20),

    /** 单通道探测窗口：十个百毫秒桶，共一秒。 */
    PROBE(SeatSelectionSampleType.PROBE, 10);

    /** 每个 Redis 统计桶固定为一百毫秒。 */
    public static final long BUCKET_MILLIS = 100L;

    /** 统计桶保留五秒，覆盖最长两秒窗口并为读取抖动预留余量。 */
    public static final long STATISTICS_TTL_MILLIS = 5_000L;

    private final SeatSelectionSampleType sampleType;
    private final int bucketCount;

    SeatSelectionWindow(SeatSelectionSampleType sampleType, int bucketCount) {
        this.sampleType = sampleType;
        this.bucketCount = bucketCount;
    }

    /**
     * 返回当前窗口汇总的样本类型。
     *
     * @return 常态或探测样本类型
     */
    public SeatSelectionSampleType sampleType() {
        // 普通流量与探测流量分开存储，避免恢复样本污染进入单通道的判断。
        return sampleType;
    }

    /**
     * 返回当前窗口需要汇总的百毫秒桶数量。
     *
     * @return 固定桶数量
     */
    public int bucketCount() {
        // 窗口时长由桶宽和桶数共同确定，调用方不再自行拼装两项参数。
        return bucketCount;
    }
}
