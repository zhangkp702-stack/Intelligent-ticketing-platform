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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 有界扫描 Redis 座位位图与 owner Hash 的一致性，仅告警不自动清理未知 owner。
 *
 * <p>未落库的正常请求在极短窗口内同样可能没有 reservation 记录，因此扫描器只输出人工处理线索，
 * 不根据 Redis key 反推并释放座位，以免误释放正在执行的购票请求。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSeatBitmapConsistencyScanner {

    private static final String BITMAP_KEY_PREFIX = "index12306-ticket-service:seat_bitmap:";
    private static final String OWNER_KEY_PREFIX = "index12306-ticket-service:seat_owner:";

    private final DistributedCache distributedCache;

    @Value("${index12306.ticket.redis-seat-consistency.enabled:true}")
    private boolean redisSeatConsistencyScanEnabled;

    @Value("${index12306.ticket.redis-seat-consistency.max-keys-per-run:20}")
    private int maxKeysPerRun;

    /**
     * 定时有限扫描座位 bitmap 和 owner Hash，发现数量或字段不一致时记录可人工定位的 key。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.redis-seat-consistency.interval-millis:60000}")
    public void scanBitmapOwnerConsistency() {
        // 支持通过开关暂停诊断扫描，避免 Redis 故障期间为诊断额外制造读压力。
        if (!redisSeatConsistencyScanEnabled) {
            return;
        }
        int scanLimit = Math.max(1, maxKeysPerRun);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        ScanOptions options = ScanOptions.scanOptions().match(BITMAP_KEY_PREFIX + "*").count(scanLimit).build();
        try (Cursor<String> bitmapKeys = stringRedisTemplate.scan(options)) {
            int scanned = 0;
            // 每轮严格限制检查 key 数量，避免一次诊断扫描挤占购票 Redis 的正常命令配额。
            while (bitmapKeys.hasNext() && scanned++ < scanLimit) {
                inspectBitmapOwnerPair(stringRedisTemplate, bitmapKeys.next());
            }
        } catch (RuntimeException ex) {
            // 一致性诊断必须 fail-open；Redis 访问异常不能影响购票或关闭订单主链路。
            log.warn("ticket_redis_bitmap_consistency_scan_failed", ex);
        }
    }

    /**
     * 比对一个 bitmap 与同分片 owner Hash 的位数和逐字段对应关系。
     *
     * @param stringRedisTemplate Redis 访问模板
     * @param bitmapKey 当前座位 bitmap key
     */
    private void inspectBitmapOwnerPair(StringRedisTemplate stringRedisTemplate, String bitmapKey) {
        String ownerKey = buildOwnerKey(bitmapKey);
        Map<Object, Object> owners = stringRedisTemplate.opsForHash().entries(ownerKey);
        Long occupiedBits = stringRedisTemplate.execute((RedisCallback<Long>) connection -> connection.stringCommands()
                .bitCount(bitmapKey.getBytes(StandardCharsets.UTF_8)));
        long bitmapSeatCount = occupiedBits == null ? 0L : occupiedBits;

        // bitmap bit 数超过 owner 字段数表示至少有座位没有 owner，不能自动删除，只记录人工核对线索。
        if (bitmapSeatCount > owners.size()) {
            log.error("ticket_redis_bitmap_without_owner bitmapKey={}, ownerKey={}, bitmapSeatCount={}, ownerCount={}",
                    bitmapKey, ownerKey, bitmapSeatCount, owners.size());
        }
        // owner 字段逐一核对 bitmap 位，owner 残留或字段格式损坏都要作为独立异常输出。
        owners.forEach((seatBit, reservationId) -> inspectOwnerBit(stringRedisTemplate, bitmapKey, ownerKey,
                String.valueOf(seatBit), String.valueOf(reservationId)));
    }

    /**
     * 校验 owner Hash 中一个座位 bit 是否仍在 bitmap 中占用。
     *
     * @param stringRedisTemplate Redis 访问模板
     * @param bitmapKey 当前座位 bitmap key
     * @param ownerKey 当前 owner Hash key
     * @param seatBit owner 字段中的座位 bit
     * @param reservationId owner 记录的 reservation 标识
     */
    private void inspectOwnerBit(StringRedisTemplate stringRedisTemplate, String bitmapKey, String ownerKey,
                                 String seatBit, String reservationId) {
        try {
            long bitOffset = Long.parseLong(seatBit);
            // owner 存在但 bitmap 未占用说明释放过程部分成功，保留 owner 供人工核查而非猜测性删除。
            if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(bitmapKey, bitOffset))) {
                log.error("ticket_redis_owner_without_bitmap bitmapKey={}, ownerKey={}, seatBit={}, reservationId={}",
                        bitmapKey, ownerKey, seatBit, reservationId);
            }
        } catch (NumberFormatException ex) {
            // 非数字字段不可能来自正常 Lua 占位，输出完整定位信息供人工修复。
            log.error("ticket_redis_owner_invalid_bit ownerKey={}, seatBit={}, reservationId={}",
                    ownerKey, seatBit, reservationId, ex);
        }
    }

    /**
     * 由 bitmap key 推导同 hash tag 下的 owner Hash key。
     *
     * @param bitmapKey 座位位图 key
     * @return 对应座位 owner key
     */
    private String buildOwnerKey(String bitmapKey) {
        if (!bitmapKey.startsWith(BITMAP_KEY_PREFIX)) {
            throw new IllegalArgumentException("不是座位 bitmap key");
        }
        // 保留原始 hash tag 和分片后缀，使位图与 owner 始终定位到同一运行库存。
        return OWNER_KEY_PREFIX + bitmapKey.substring(BITMAP_KEY_PREFIX.length());
    }
}
