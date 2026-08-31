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

package org.opengoofy.index12306.biz.orderservice.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCommandStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.service.impl.OrderCommandExecutionServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证订单创建 Command 的唯一租约、围栏递增和定向分片恢复行为。
 */
@ExtendWith(MockitoExtension.class)
class OrderCommandExecutionServiceTests {

    @Mock
    private OrderCommandMapper orderCommandMapper;

    @Mock
    private OrderService orderService;

    private OrderCommandExecutionServiceImpl executionService;

    /**
     * 使用当前线程执行器构造可确定完成时点的租约执行服务。
     */
    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        executionService = new OrderCommandExecutionServiceImpl(
                orderCommandMapper, orderService, directExecutor);
        // 测试显式固定租约和恢复批次，避免依赖 Spring 属性注入。
        ReflectionTestUtils.setField(executionService, "claimLeaseMillis", 120_000L);
        ReflectionTestUtils.setField(executionService, "recoveryShardsPerRun", 1);
        ReflectionTestUtils.setField(executionService, "recoveryBatchSizePerShard", 8);
        ReflectionTestUtils.setField(executionService, "unknownRecheckMillis", 300_000L);
    }

    /**
     * 验证只有条件更新成功的实例能够取得递增 token 后执行订单事务。
     */
    @Test
    void executesOnlyAfterAtomicLeaseClaim() {
        OrderCommandDO claimed = claimedCommand();
        when(orderCommandMapper.update(isNull(), any())).thenReturn(1);
        when(orderCommandMapper.selectOne(any())).thenReturn(claimed);

        executionService.dispatchAsync("1001", "action-1:create-order");

        verify(orderService).executeClaimedOrderCommand(claimed);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> claimCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderCommandMapper).update(isNull(), claimCaptor.capture());
        String sqlSet = ((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>)
                claimCaptor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("attempt_count = attempt_count + 1", "lease_token = lease_token + 1");
        assertThat(claimCaptor.getValue().getSqlSegment())
                .contains("user_id", "command_id", "attempt_count < max_attempts");
    }

    /**
     * 验证未赢得租约的重复调度不会进入订单创建，只尝试收敛可能耗尽的过期任务。
     */
    @Test
    void skipsExecutionWhenLeaseClaimLoses() {
        when(orderCommandMapper.update(isNull(), any())).thenReturn(0);

        executionService.dispatchAsync("1001", "action-1:create-order");

        verify(orderService, never()).executeClaimedOrderCommand(any());
        verify(orderCommandMapper, times(2)).update(isNull(), any());
    }

    /**
     * 验证恢复器使用 recovery_shard 等值路由，并且 UNKNOWN 只触发订单事实对账。
     */
    @Test
    void recoveryScanRoutesOneShardAndReconcilesUnknown() {
        OrderCommandDO unknown = OrderCommandDO.builder()
                .commandId("action-1:create-order")
                .actionId("action-1")
                .userId("1001")
                .recoveryShard(0)
                .requestFingerprint("fingerprint")
                .sourceMessageId("reservation-1")
                .reservationId("reservation-1")
                .status(OrderCommandStatusEnum.UNKNOWN.name())
                .nextRetryTime(new Date(0L))
                .build();
        when(orderCommandMapper.selectList(any())).thenReturn(List.of(unknown));

        executionService.recoverDueCommands();

        verify(orderService).reconcileUnknownOrderCommand(unknown);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderCommandMapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
                .contains("recovery_shard", "status", "lease_expire_time", "next_retry_time", "LIMIT 8");
    }

    /**
     * 构造已经由当前实例持有租约的完整 Command。
     *
     * @return 可交给订单事务执行的 Command
     */
    private OrderCommandDO claimedCommand() {
        return OrderCommandDO.builder()
                .commandId("action-1:create-order")
                .actionId("action-1")
                .userId("1001")
                .recoveryShard(0)
                .requestFingerprint("fingerprint")
                .sourceMessageId("reservation-1")
                .reservationId("reservation-1")
                .requestPayload("{}")
                .status(OrderCommandStatusEnum.PROCESSING.name())
                .attemptCount(1)
                .maxAttempts(16)
                .leaseOwner("worker-a")
                .leaseToken(1L)
                .leaseExpireTime(new Date(System.currentTimeMillis() + 120_000L))
                .build();
    }
}
