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

/**
 * 从业务请求提取通用可靠命令字段的适配器。
 *
 * @param <REQ> 业务请求类型
 */
public interface ReliableCommandDescriptor<REQ> {

    /**
     * 返回命令命名空间。
     *
     * @return 稳定且不包含个人敏感信息的命名空间
     */
    String namespace();

    /**
     * 从请求提取稳定命令标识。
     *
     * @param request 业务请求
     * @return 同一业务意图重试时保持不变的命令标识
     */
    String commandId(REQ request);

    /**
     * 从请求提取命令所属主体。
     *
     * @param request 业务请求
     * @return 用户、租户或消费者标识
     */
    String ownerId(REQ request);

    /**
     * 从后端已经校验的业务字段提取数据库分片路由键。
     *
     * @param request 业务请求
     * @return 可将命令记录路由到业务副作用所在物理库的稳定值
     */
    String routingKey(REQ request);

    /**
     * 从请求提取稳定业务命令类型。
     *
     * @param request 业务请求
     * @return 不包含用户输入的固定命令类型
     */
    String commandType(REQ request);

    /**
     * 声明该命令的副作用执行模式。
     *
     * @return 执行模式
     */
    ReliableCommandMode mode();

    /**
     * 提取参与请求摘要计算的业务字段。
     *
     * @param request 业务请求
     * @return 不包含 commandId 的规范化摘要载荷
     */
    Object fingerprintPayload(REQ request);

    /**
     * 返回摘要规则版本，字段集合或规范化方式变更时必须递增。
     *
     * @return 摘要规则版本
     */
    default String fingerprintVersion() {
        return "v1";
    }

    /**
     * 提取可用于后续权威查询的安全业务引用。
     *
     * @param request 业务请求
     * @return 订单号等安全引用，暂无引用时返回 null
     */
    default String businessReference(REQ request) {
        return null;
    }

    /**
     * 将业务请求转换为不依赖业务类型的可靠命令定义。
     *
     * @param request 业务请求
     * @param fingerprint 请求摘要计算器
     * @return 可供持久化层认领的命令定义
     */
    default ReliableCommandDefinition describe(REQ request, ReliableCommandFingerprint fingerprint) {
        // Descriptor 只决定业务字段如何映射，摘要算法和持久化行为由框架统一实现。
        String requestFingerprint = fingerprint.calculate(fingerprintPayload(request));
        return new ReliableCommandDefinition(
                new ReliableCommandKey(namespace(), commandId(request), routingKey(request)),
                commandType(request),
                mode(),
                ownerId(request),
                requestFingerprint,
                fingerprintVersion(),
                businessReference(request));
    }
}
