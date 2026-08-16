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

import cn.hutool.core.lang.Singleton;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.SEAT_SELECTION_STRATEGY_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.SEAT_SELECTION_STRATEGY_RESERVATIONS;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.SEAT_SELECTION_STRATEGY_STATE;
import static org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.SeatSelectionWindow.BUCKET_MILLIS;
import static org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.SeatSelectionWindow.STATISTICS_TTL_MILLIS;

/**
 * 使用 Redis 共享百毫秒冲突统计，保证多个 ticket-service 实例读取相同窗口样本。
 */
@Component
@RequiredArgsConstructor
public class SeatSelectionStrategyRedisStore {

    private static final String LUA_RECORD_PATH = "lua/seat_strategy_record.lua";
    private static final String LUA_SNAPSHOT_PATH = "lua/seat_strategy_snapshot.lua";
    private static final String LUA_STATE_TRANSITION_PATH = "lua/seat_strategy_state_transition.lua";
    private static final long STATE_TTL_MILLIS = 60_000L;

    private final DistributedCache distributedCache;

    /**
     * 记录一次 Redis Lua 座位占位结果。
     *
     * @param strategyKey 座位策略库存维度
     * @param sampleType 常态或探测样本
     * @param reservationId 当前座位占用标识
     * @param conflict 是否发生座位占用冲突
     * @param nowMillis 当前时间戳
     */
    public void record(String strategyKey,
                       SeatSelectionSampleType sampleType,
                       String reservationId,
                       boolean conflict,
                       long nowMillis) {
        // 当前时间只命中一个固定桶，Lua 原子更新计数并登记 reservation 去重样本。
        long bucketId = nowMillis / BUCKET_MILLIS;
        List<String> keys = List.of(buildBucketKey(strategyKey, sampleType, bucketId),
                buildReservationKey(strategyKey, sampleType, bucketId));
        Long result = redisTemplate().execute(script(LUA_RECORD_PATH, Long.class), keys,
                conflict ? "1" : "0", reservationId, String.valueOf(STATISTICS_TTL_MILLIS));
        if (result == null || result != 1L) {
            throw new IllegalStateException("Redis 座位策略统计写入失败");
        }
    }

    /**
     * 汇总截止当前时刻最近若干个百毫秒桶。
     *
     * @param strategyKey 座位策略库存维度
     * @param window 阶段三约定的固定统计窗口
     * @param nowMillis 当前时间戳
     * @return 最近窗口的尝试数、冲突数和不同 reservation 数
     */
    public SeatConflictStatistics snapshot(String strategyKey,
                                           SeatSelectionWindow window,
                                           long nowMillis) {
        // 统计键和 reservation HLL 键使用相同 Hash Tag，可由一个 Lua 在集群单 Slot 内汇总。
        long currentBucketId = nowMillis / BUCKET_MILLIS;
        int bucketCount = window.bucketCount();
        List<String> keys = new ArrayList<>(bucketCount * 2);
        for (int offset = bucketCount - 1; offset >= 0; offset--) {
            keys.add(buildBucketKey(strategyKey, window.sampleType(), currentBucketId - offset));
        }
        for (int offset = bucketCount - 1; offset >= 0; offset--) {
            keys.add(buildReservationKey(strategyKey, window.sampleType(), currentBucketId - offset));
        }
        List<?> result = redisTemplate().execute(script(LUA_SNAPSHOT_PATH, List.class), keys, String.valueOf(bucketCount));
        if (result == null || result.size() < 3) {
            return new SeatConflictStatistics(0L, 0L, 0L);
        }
        // Redis 数字结果统一转换为 long，避免不同客户端序列化实现造成类型差异。
        return new SeatConflictStatistics(number(result.get(0)), number(result.get(1)), number(result.get(2)));
    }

    /**
     * 根据共享统计原子评估并迁移一个库存维度的选座状态。
     *
     * @param strategyKey 座位策略库存维度
     * @param normalStatistics 常态乐观占位快速窗口统计
     * @param probeStatistics 探测乐观占位窗口统计
     * @param availableSeats 当前候选车厢可售座位数
     * @param config 状态机的阈值与时间边界
     * @param nowMillis 当前时间戳
     * @return Lua 原子迁移后的共享状态快照
     */
    public SeatSelectionStrategyState transition(String strategyKey,
                                                 SeatConflictStatistics normalStatistics,
                                                 SeatConflictStatistics probeStatistics,
                                                 int availableSeats,
                                                 SeatSelectionStrategyStateConfig config,
                                                 long nowMillis) {
        // 单个 Hash 状态保存全部字段，Lua 在同一 Redis slot 中读取统计输入并提交完整的新状态。
        List<?> result = redisTemplate().execute(script(LUA_STATE_TRANSITION_PATH, List.class), List.of(buildStateKey(strategyKey)),
                String.valueOf(nowMillis),
                String.valueOf(config.evaluationIntervalMillis()),
                String.valueOf(config.minimumAttempts()),
                String.valueOf(config.conflictRateThresholdBps()),
                String.valueOf(config.recoveryConflictRateThresholdBps()),
                String.valueOf(config.lowStockThreshold()),
                String.valueOf(config.singleMinimumResidenceMillis()),
                String.valueOf(config.probePercentage()),
                String.valueOf(config.healthyPeriodsRequired()),
                String.valueOf(normalStatistics.attempts()),
                String.valueOf(normalStatistics.conflicts()),
                String.valueOf(probeStatistics.attempts()),
                String.valueOf(probeStatistics.conflicts()),
                String.valueOf(availableSeats),
                String.valueOf(STATE_TTL_MILLIS));
        if (result == null || result.size() < 7) {
            throw new IllegalStateException("Redis 座位策略状态迁移失败");
        }
        // Redis 客户端的 Lua 返回类型可能是 Long 或 String，统一转为领域状态避免调用方感知序列化差异。
        return new SeatSelectionStrategyState(SeatSelectionStrategyMode.valueOf(String.valueOf(result.get(0))),
                number(result.get(1)), number(result.get(2)), number(result.get(3)), (int) number(result.get(4)),
                (int) number(result.get(5)), String.valueOf(result.get(6)));
    }

    /**
     * 获取项目统一的字符串 Redis 客户端。
     *
     * @return 字符串 Redis 客户端
     */
    private StringRedisTemplate redisTemplate() {
        // 复用项目 DistributedCache 配置，保持密码、连接池和序列化方式一致。
        return (StringRedisTemplate) distributedCache.getInstance();
    }

    /**
     * 按脚本路径和结果类型创建可复用脚本定义。
     *
     * @param path classpath 下的 Lua 路径
     * @param resultType Redis 返回类型
     * @param <T> Redis 返回泛型
     * @return 可被 StringRedisTemplate 执行的脚本
     */
    private <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        // Singleton 避免每次占位统计都重新解析 Lua 资源。
        DefaultRedisScript<T> script = Singleton.get(path, () -> {
            DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
            redisScript.setResultType(resultType);
            return redisScript;
        });
        Assert.notNull(script);
        return script;
    }

    /**
     * 构造冲突统计桶键。
     *
     * @param strategyKey 座位策略库存维度
     * @param sampleType 样本类型
     * @param bucketId 百毫秒桶编号
     * @return Redis 统计桶键
     */
    private String buildBucketKey(String strategyKey, SeatSelectionSampleType sampleType, long bucketId) {
        // 桶编号放在 Hash Tag 外，确保同库存维度全部桶落在同一 Slot。
        return String.format(SEAT_SELECTION_STRATEGY_BUCKET, strategyKey, sampleType.value(), bucketId);
    }

    /**
     * 构造 reservation 去重统计键。
     *
     * @param strategyKey 座位策略库存维度
     * @param sampleType 样本类型
     * @param bucketId 百毫秒桶编号
     * @return Redis HyperLogLog 键
     */
    private String buildReservationKey(String strategyKey, SeatSelectionSampleType sampleType, long bucketId) {
        // HyperLogLog 仅用于控制单个请求对策略的影响，不参与库存正确性判断。
        return String.format(SEAT_SELECTION_STRATEGY_RESERVATIONS, strategyKey, sampleType.value(), bucketId);
    }

    /**
     * 构造一个库存维度唯一的策略状态键。
     *
     * @param strategyKey 座位策略库存维度
     * @return 使用 Redis Cluster Hash Tag 的状态键
     */
    private String buildStateKey(String strategyKey) {
        // 状态、统计桶和 reservation HLL 使用相同 Hash Tag，便于后续扩展为单脚本联合读取。
        return String.format(SEAT_SELECTION_STRATEGY_STATE, strategyKey);
    }

    /**
     * 把 Redis Lua 数值结果安全转换为 long。
     *
     * @param value Redis 返回值
     * @return long 数值
     */
    private long number(Object value) {
        // Lettuce 通常返回 Long，字符串回退便于兼容测试客户端。
        return value instanceof Number number ? number.longValue() : parseLong(String.valueOf(value));
    }

    /**
     * 解析可缺失的 Redis long 字段。
     *
     * @param value Redis 字段值
     * @return 缺失或非法时返回零
     */
    private long parseLong(String value) {
        // 历史或部分写入状态缺失字段时按零处理，状态机可以重新收敛。
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
