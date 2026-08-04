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

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.BusinessOperationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.BusinessOperationMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.BusinessOperationAuditMapper;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证业务操作短事务服务对唯一键竞争的处理。
 */
class BusinessOperationTransactionServiceTests {

    /**
     * 验证主键冲突被识别为未获得执行权。
     */
    @Test
    void returnsFalseWhenAnotherInstanceAlreadyClaimedOperation() {
        BusinessOperationMapper mapper = mock(BusinessOperationMapper.class);
        BusinessOperationTransactionService service = new BusinessOperationTransactionService(
                mapper, mock(BusinessOperationAuditMapper.class));
        BusinessOperationDO operation = BusinessOperationDO.builder()
                .operationId("action-1")
                .status(BusinessOperationTransactionService.STATUS_PROCESSING)
                .build();
        when(mapper.insert(operation)).thenThrow(new DuplicateKeyException("duplicate operation"));

        // 唯一键竞争是正常幂等分支，不向上抛出数据库异常。
        assertThat(service.tryClaim(operation)).isFalse();
    }

}
