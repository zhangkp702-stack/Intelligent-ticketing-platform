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

package org.opengoofy.index12306.framework.starter.reliablecommand.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证可靠命令核心模型不依赖具体业务服务即可稳定工作。
 */
class ReliableCommandCoreTests {

    /**
     * 验证等价 Map 的构造顺序不会改变请求摘要。
     */
    @Test
    void shouldGenerateCanonicalFingerprint() {
        ReliableCommandFingerprint fingerprint = new JacksonReliableCommandFingerprint(new ObjectMapper());
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("trainId", "G1");
        first.put("userId", "1001");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("userId", "1001");
        second.put("trainId", "G1");

        // Map 键顺序不同但业务字段相同，必须绑定到同一个请求摘要。
        assertThat(fingerprint.calculate(first)).isEqualTo(fingerprint.calculate(second));
        second.put("trainId", "G2");
        assertThat(fingerprint.calculate(first)).isNotEqualTo(fingerprint.calculate(second));
    }

    /**
     * 验证业务 Descriptor 只负责字段提取，框架统一构造可靠命令定义。
     */
    @Test
    void shouldBuildDefinitionFromDescriptor() {
        ReliableCommandDescriptor<TestRequest> descriptor = new ReliableCommandDescriptor<>() {
            @Override
            public String namespace() {
                return "ticket.purchase";
            }

            @Override
            public String commandId(TestRequest request) {
                return request.commandId();
            }

            @Override
            public String ownerId(TestRequest request) {
                return request.userId();
            }

            @Override
            public String routingKey(TestRequest request) {
                return request.userId();
            }

            @Override
            public String commandType(TestRequest request) {
                return "PURCHASE_TICKET";
            }

            @Override
            public ReliableCommandMode mode() {
                return ReliableCommandMode.REMOTE_EFFECT;
            }

            @Override
            public Object fingerprintPayload(TestRequest request) {
                return Map.of("questionId", request.questionId());
            }
        };

        // Descriptor 不持久化原始请求，只输出后续通用存储需要的稳定元数据。
        ReliableCommandDefinition definition = descriptor.describe(
                new TestRequest("cmd-1", "user-1", "question-1"),
                new JacksonReliableCommandFingerprint(new ObjectMapper()));

        assertThat(definition.key()).isEqualTo(new ReliableCommandKey("ticket.purchase", "cmd-1", "user-1"));
        assertThat(definition.ownerId()).isEqualTo("user-1");
        assertThat(definition.commandType()).isEqualTo("PURCHASE_TICKET");
        assertThat(definition.mode()).isEqualTo(ReliableCommandMode.REMOTE_EFFECT);
        assertThat(definition.fingerprintVersion()).isEqualTo("v1");
        assertThat(definition.requestFingerprint()).hasSize(64);
    }

    /**
     * 验证状态机允许安全恢复路径并拒绝终态重开。
     */
    @Test
    void shouldEnforceReliableStateTransitions() {
        assertThat(ReliableCommandStateMachine.canTransition(
                ReliableCommandStatus.PROCESSING, ReliableCommandStatus.UNKNOWN)).isTrue();
        assertThat(ReliableCommandStateMachine.canTransition(
                ReliableCommandStatus.UNKNOWN, ReliableCommandStatus.RECONCILING)).isTrue();
        assertThat(ReliableCommandStateMachine.canTransition(
                ReliableCommandStatus.RECONCILING, ReliableCommandStatus.SUCCEEDED)).isTrue();

        // 已成功命令不能再次进入处理中，避免同一业务意图产生第二次副作用。
        assertThatThrownBy(() -> ReliableCommandStateMachine.requireTransition(
                ReliableCommandStatus.SUCCEEDED, ReliableCommandStatus.PROCESSING))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 测试用最小业务请求。
     *
     * @param commandId 稳定命令标识
     * @param userId 用户标识
     * @param questionId 问题标识
     */
    private record TestRequest(String commandId, String userId, String questionId) {
    }
}
