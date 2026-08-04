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

import java.util.Objects;

/**
 * 一次可靠命令认领所需的稳定元数据，不包含原始请求正文。
 *
 * @param key 稳定命令键
 * @param commandType 稳定业务命令类型
 * @param mode 副作用执行模式
 * @param ownerId 命令所属用户、租户或消费者
 * @param requestFingerprint 规范化请求摘要
 * @param fingerprintVersion 摘要规则版本
 * @param businessReference 可选的安全业务引用
 */
public record ReliableCommandDefinition(
        ReliableCommandKey key,
        String commandType,
        ReliableCommandMode mode,
        String ownerId,
        String requestFingerprint,
        String fingerprintVersion,
        String businessReference) {

    /**
     * 校验命令定义具备重复请求比较所需的完整字段。
     *
     * @param key 稳定命令键
     * @param commandType 稳定业务命令类型
     * @param mode 副作用执行模式
     * @param ownerId 命令所属主体
     * @param requestFingerprint 请求摘要
     * @param fingerprintVersion 摘要规则版本
     * @param businessReference 可选业务引用
     */
    public ReliableCommandDefinition {
        // 所有持久化比较字段必须在首次认领前确定，后续不得根据执行结果重新生成。
        key = Objects.requireNonNull(key, "key");
        commandType = requireText(commandType, "commandType", 64);
        mode = Objects.requireNonNull(mode, "mode");
        ownerId = requireText(ownerId, "ownerId", 128);
        requestFingerprint = requireText(requestFingerprint, "requestFingerprint", 128);
        fingerprintVersion = requireText(fingerprintVersion, "fingerprintVersion", 32);
        businessReference = normalizeOptional(businessReference, 256, "businessReference");
    }

    /**
     * 校验必填文本字段。
     *
     * @param value 原始值
     * @param fieldName 字段名称
     * @param maxLength 最大长度
     * @return 规范化后的文本
     */
    private static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalizeOptional(value, maxLength, fieldName);
    }

    /**
     * 规范化允许为空的文本字段。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @return 规范化后的值，空白值返回 null
     */
    private static String normalizeOptional(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
