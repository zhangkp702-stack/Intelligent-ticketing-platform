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

package org.opengoofy.index12306.biz.userservice.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.userservice.dao.entity.PassengerDO;
import org.opengoofy.index12306.biz.userservice.dao.mapper.PassengerMapper;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 验证乘车人查询的空结果语义和数据库访问边界。
 */
@ExtendWith(MockitoExtension.class)
class PassengerServiceImplTests {

    @Mock
    private PassengerMapper passengerMapper;

    @Mock
    private DistributedCache distributedCache;

    /**
     * 验证数据库没有匹配记录时返回空列表，而不是会被 MCP 误判为失败的 null。
     */
    @Test
    void returnsEmptyListWhenPassengerQueryHasNoRows() {
        PassengerServiceImpl service = new PassengerServiceImpl(passengerMapper, distributedCache);
        // Mapper 的正常空查询结果必须转换为稳定的业务空数组。
        when(passengerMapper.selectList(any())).thenReturn(List.<PassengerDO>of());

        assertThat(service.listPassengerQueryByUsername("alice")).isEmpty();
    }
}
