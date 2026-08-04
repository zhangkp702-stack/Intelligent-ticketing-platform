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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.BusinessOperationDO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.BusinessOperationStatusRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证业务操作状态查询的用户归属和脱敏响应边界。
 */
class BusinessOperationCoordinatorStatusTests {

    private BusinessOperationTransactionService transactionService;
    private BusinessOperationCoordinator coordinator;

    /**
     * 创建隔离依赖并设置当前用户上下文。
     */
    @BeforeEach
    void setUp() {
        // 状态查询只依赖操作事实表，使用 Mock 排除真实数据库影响。
        transactionService = mock(BusinessOperationTransactionService.class);
        coordinator = new BusinessOperationCoordinator(
                transactionService, mock(BusinessOperationLeaseService.class));
        UserContext.setUser(UserInfoDTO.builder().userId("user-1").username("alice").build());
    }

    /**
     * 清理测试线程用户上下文。
     */
    @AfterEach
    void tearDown() {
        // UserContext 是线程本地状态，必须避免泄漏到其他用例。
        UserContext.removeUser();
    }

    /**
     * 验证成功取消只返回 Agent 恢复所需的白名单字段。
     */
    @Test
    void returnsSafeSucceededCancellationStatus() {
        BusinessOperationDO operation = operation("user-1", "CANCEL_TICKET_ORDER", 1);
        operation.setResultJson("true");
        when(transactionService.findById("action-1")).thenReturn(operation);

        // 对账响应不暴露原始业务返回或用户敏感信息。
        BusinessOperationStatusRespDTO result = coordinator.getStatus("action-1");

        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getOperationType()).isEqualTo("CANCEL_TICKET_ORDER");
        assertThat(result.getSafeResultJson()).isEqualTo("{\"cancelled\":true}");
    }

    /**
     * 验证其他用户不能通过 actionId 探测操作状态。
     */
    @Test
    void rejectsOperationOwnedByAnotherUser() {
        when(transactionService.findById("action-1"))
                .thenReturn(operation("user-2", "PURCHASE_TICKET", 0));

        // 不区分不存在和无权访问，避免 actionId 枚举泄露。
        assertThatThrownBy(() -> coordinator.getStatus("action-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("业务操作不存在");
    }

    /**
     * 创建测试业务操作事实。
     *
     * @param userId 所属用户
     * @param operationType 操作类型
     * @param status 状态码
     * @return 业务操作实体
     */
    private BusinessOperationDO operation(String userId, String operationType, int status) {
        // 固定 actionId 便于聚焦状态与归属校验。
        return BusinessOperationDO.builder()
                .operationId("action-1")
                .operationType(operationType)
                .userId(userId)
                .status(status)
                .build();
    }
}
