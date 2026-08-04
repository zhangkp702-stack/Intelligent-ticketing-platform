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
 * 可靠命令的稳定业务身份。
 *
 * @param namespace 命令类型或消费者命名空间，不应包含个人敏感信息
 * @param commandId 同一业务意图重试时保持不变的命令标识
 * @param routingKey 由后端业务字段提取的数据库分片路由键
 */
public record ReliableCommandKey(String namespace, String commandId, String routingKey) {

    private static final int MAX_NAMESPACE_LENGTH = 64;
    private static final int MAX_COMMAND_ID_LENGTH = 128;
    private static final int MAX_ROUTING_KEY_LENGTH = 128;

    /**
     * 校验命令键满足数据库主键和安全日志边界。
     *
     * @param namespace 命令命名空间
     * @param commandId 稳定命令标识
     * @param routingKey 数据库分片路由键
     */
    public ReliableCommandKey {
        // 统一裁剪文本，避免调用方因无意义空白创建两个业务命令。
        namespace = requireText(namespace, "namespace", MAX_NAMESPACE_LENGTH);
        commandId = requireText(commandId, "commandId", MAX_COMMAND_ID_LENGTH);
        routingKey = requireText(routingKey, "routingKey", MAX_ROUTING_KEY_LENGTH);
    }

    /**
     * 校验并规范化键字段。
     *
     * @param value 原始字段值
     * @param fieldName 字段名称
     * @param maxLength 最大允许长度
     * @return 去除首尾空白后的字段值
     */
    private static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
