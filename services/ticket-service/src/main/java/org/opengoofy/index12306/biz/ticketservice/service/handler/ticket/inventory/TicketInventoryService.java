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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.inventory;

import com.alibaba.fastjson2.JSON;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一余票库存访问入口，负责查询、原子扣减和幂等回补。
 */
@Slf4j
@Component
public class TicketInventoryService {

    private static final String INVENTORY_OPERATION_METRIC = "index12306.ticket.inventory.operation";
    private static final String LUA_DEDUCT_PATH = "lua/ticket_inventory_deduct.lua";
    private static final String LUA_ROLLBACK_PATH = "lua/ticket_inventory_rollback.lua";

    private final DistributedCache distributedCache;
    private final Map<String, Counter> operationCounters;

    /**
     * 创建统一库存访问组件并预注册低基数操作指标。
     *
     * @param distributedCache Redis 分布式缓存入口
     * @param meterRegistry 应用指标注册表
     */
    public TicketInventoryService(DistributedCache distributedCache, MeterRegistry meterRegistry) {
        // 预注册固定结果指标，避免把车次、站点或日期写入监控标签造成高基数。
        this.distributedCache = distributedCache;
        this.operationCounters = Map.of(
                "deduct_success", registerOperationCounter(meterRegistry, "deduct", "success"),
                "deduct_rejected", registerOperationCounter(meterRegistry, "deduct", "rejected"),
                "deduct_missing", registerOperationCounter(meterRegistry, "deduct", "missing"),
                "deduct_repeated", registerOperationCounter(meterRegistry, "deduct", "repeated"),
                "deduct_failed", registerOperationCounter(meterRegistry, "deduct", "failed"),
                "rollback_success", registerOperationCounter(meterRegistry, "rollback", "success"),
                "rollback_repeated", registerOperationCounter(meterRegistry, "rollback", "repeated"),
                "rollback_missing", registerOperationCounter(meterRegistry, "rollback", "missing"),
                "rollback_failed", registerOperationCounter(meterRegistry, "rollback", "failed"));
    }

    /**
     * 从统一库存 Hash 查询指定区间和席别的数量，缓存尚未建立时返回空。
     *
     * @param trainId 车次标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 席别类型
     * @return 已存在的统一库存数量，未初始化时为空
     */
    public Optional<Integer> query(String trainId, Date serviceDate, String departure, String arrival, Integer seatType) {
        // 统一库存读取只访问对应 Hash，不触发数据库回源或其他缓存写入。
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Object quantity = stringRedisTemplate.opsForHash().get(
                TicketInventoryKeyUtil.buildInventoryKey(trainId, serviceDate),
                TicketInventoryKeyUtil.buildInventoryField(departure, arrival, seatType));
        return Optional.ofNullable(quantity).map(Object::toString).map(Integer::parseInt);
    }

    /**
     * 原子扣减统一库存的全部受影响区间，并将库存不足转换为正常的无票结果。
     *
     * @param trainId 车次标识
     * @param serviceDate 列车始发日期
     * @param affectedRoutes 本次购票影响的全部区间
     * @param seatTypeCountMap 各席别购买数量
     * @param reservationId 本次购票预订标识
     * @return 库存足够或同一预订已扣减时返回 true，库存不足返回 false
     */
    public boolean deductInUnified(String trainId, Date serviceDate, List<RouteDTO> affectedRoutes,
                                   Map<Integer, Long> seatTypeCountMap, String reservationId) {
        Map<String, Long> inventoryDeltas = buildInventoryDeltas(affectedRoutes, seatTypeCountMap);
        if (inventoryDeltas.isEmpty()) {
            recordOperation("deduct", "missing");
            throw new ServiceException("购票参数缺少有效库存区间");
        }
        String inventoryKey = TicketInventoryKeyUtil.buildInventoryKey(trainId, serviceDate);
        String operationMarkerKey = TicketInventoryKeyUtil.buildOperationMarkerKey(trainId, serviceDate);
        try {
            // Lua 先校验所有字段，再一次性扣减并写入预订标记，统一库存是唯一裁决来源。
            Long result = ((StringRedisTemplate) distributedCache.getInstance()).execute(
                    getLongScript(LUA_DEDUCT_PATH), List.of(inventoryKey, operationMarkerKey),
                    JSON.toJSONString(inventoryDeltas), reservationId);
            if (Long.valueOf(0L).equals(result) || Long.valueOf(3L).equals(result)) {
                recordDeductResult(result, trainId, serviceDate);
                return true;
            }
            recordDeductResult(result, trainId, serviceDate);
            if (Long.valueOf(1L).equals(result)) {
                return false;
            }
            throw new ServiceException("统一库存未完成初始化，请稍候再试");
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 统一库存已成为真值，Redis 异常必须失败关闭，不能继续执行选座或建单。
            recordOperation("deduct", "failed");
            log.error("统一库存扣减失败 trainId={}, serviceDate={}, reservationId={}",
                    trainId, serviceDate, reservationId, ex);
            throw new ServiceException("统一库存扣减失败，请稍候再试");
        }
    }

    /**
     * 幂等回补已扣减的统一库存。
     *
     * @param trainId 车次标识
     * @param serviceDate 列车始发日期
     * @param affectedRoutes 本次订单影响的全部区间
     * @param seatTypeCountMap 各席别回补数量
     * @param rollbackId reservationId 等稳定幂等标识
     */
    public void rollbackInUnified(String trainId, Date serviceDate, List<RouteDTO> affectedRoutes,
                                  Map<Integer, Long> seatTypeCountMap, String rollbackId) {
        Map<String, Long> inventoryDeltas = buildInventoryDeltas(affectedRoutes, seatTypeCountMap);
        if (inventoryDeltas.isEmpty()) {
            recordOperation("rollback", "missing");
            throw new ServiceException("订单缺少有效库存区间");
        }
        try {
            String inventoryKey = TicketInventoryKeyUtil.buildInventoryKey(trainId, serviceDate);
            String operationMarkerKey = TicketInventoryKeyUtil.buildOperationMarkerKey(trainId, serviceDate);
            // 通过扣减标记约束回补，避免未成功扣减的请求凭空增加库存。
            Long result = ((StringRedisTemplate) distributedCache.getInstance()).execute(
                    getLongScript(LUA_ROLLBACK_PATH), List.of(inventoryKey, operationMarkerKey),
                    JSON.toJSONString(inventoryDeltas), rollbackId);
            recordRollbackResult(result, trainId, serviceDate, rollbackId);
            if (Long.valueOf(0L).equals(result) || Long.valueOf(1L).equals(result)) {
                return;
            }
            if (Long.valueOf(3L).equals(result)) {
                throw new ServiceException("统一库存缺少对应扣减标记，请稍候重试");
            }
            throw new ServiceException("统一库存回补失败，请稍候重试");
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 订单关闭恢复必须等待统一库存成功，供后续消息重试而不是静默丢失库存。
            recordOperation("rollback", "failed");
            log.error("统一库存回补失败 trainId={}, serviceDate={}, rollbackId={}",
                    trainId, serviceDate, rollbackId, ex);
            throw new ServiceException("统一库存回补失败，请稍候重试");
        }
    }

    /**
     * 将受影响区间和席别数量展开为统一库存字段增量。
     *
     * @param affectedRoutes 本次操作影响的区间
     * @param seatTypeCountMap 各席别变化数量
     * @return field 到正整数变化量的映射
     */
    private Map<String, Long> buildInventoryDeltas(List<RouteDTO> affectedRoutes,
                                                   Map<Integer, Long> seatTypeCountMap) {
        // 每个受影响区间都必须按席别应用同一购买或回补数量。
        Map<String, Long> result = new LinkedHashMap<>();
        affectedRoutes.forEach(route -> seatTypeCountMap.forEach((seatType, count) -> result.put(
                TicketInventoryKeyUtil.buildInventoryField(
                        route.getStartStation(), route.getEndStation(), seatType),
                count)));
        return result;
    }

    /**
     * 记录统一库存扣减脚本的固定结果。
     *
     * @param result 脚本结果，0 成功、1 库存不足、2 字段缺失、3 重复扣减
     * @param trainId 车次标识
     * @param serviceDate 列车始发日期
     */
    private void recordDeductResult(Long result, String trainId, Date serviceDate) {
        if (Long.valueOf(0L).equals(result)) {
            recordOperation("deduct", "success");
        } else if (Long.valueOf(1L).equals(result)) {
            recordOperation("deduct", "rejected");
            log.debug("统一库存扣减被库存不足拒绝 trainId={}, serviceDate={}", trainId, serviceDate);
        } else if (Long.valueOf(2L).equals(result)) {
            // 字段缺失表示统一库存初始化不完整，调用方应直接拒绝本次购票。
            recordOperation("deduct", "missing");
        } else if (Long.valueOf(3L).equals(result)) {
            recordOperation("deduct", "repeated");
        } else {
            recordOperation("deduct", "failed");
        }
    }

    /**
     * 记录统一库存回补脚本的固定结果。
     *
     * @param result 脚本结果，0 成功、1 已回补、2 字段缺失、3 未执行过扣减
     * @param trainId 车次标识
     * @param serviceDate 列车始发日期
     * @param rollbackId 稳定幂等标识
     */
    private void recordRollbackResult(Long result, String trainId, Date serviceDate, String rollbackId) {
        if (Long.valueOf(0L).equals(result)) {
            recordOperation("rollback", "success");
        } else if (Long.valueOf(1L).equals(result)) {
            recordOperation("rollback", "repeated");
        } else if (Long.valueOf(2L).equals(result)) {
            recordOperation("rollback", "missing");
        } else {
            recordOperation("rollback", "failed");
            log.debug("统一库存回补返回未知结果 trainId={}, serviceDate={}, rollbackId={}, result={}",
                    trainId, serviceDate, rollbackId, result);
        }
    }

    /**
     * 加载返回 Long 状态码的统一库存操作脚本。
     *
     * @param scriptPath Lua 脚本类路径
     * @return 已缓存的 Redis 脚本
     */
    private DefaultRedisScript<Long> getLongScript(String scriptPath) {
        // 扣减和回补脚本分别按路径缓存，热路径不重复解析类路径资源。
        return Singleton.get(scriptPath, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(scriptPath)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
    }

    /**
     * 注册统一库存写操作的固定标签计数器。
     *
     * @param meterRegistry 应用指标注册表
     * @param operation deduct 或 rollback
     * @param result 固定操作结果
     * @return 已注册并可复用的计数器
     */
    private Counter registerOperationCounter(MeterRegistry meterRegistry, String operation, String result) {
        // operation 和 result 均来自构造函数中的固定集合，不包含车次或订单等高基数信息。
        return Counter.builder(INVENTORY_OPERATION_METRIC)
                .description("Unified ticket inventory operation")
                .tags("operation", operation, "result", result)
                .register(meterRegistry);
    }

    /**
     * 增加一次统一库存写操作结果计数。
     *
     * @param operation deduct 或 rollback
     * @param result 固定操作结果
     */
    private void recordOperation(String operation, String result) {
        // 所有调用组合都在构造阶段预注册，禁止运行时动态扩展监控标签。
        operationCounters.get(operation + "_" + result).increment();
    }
}
