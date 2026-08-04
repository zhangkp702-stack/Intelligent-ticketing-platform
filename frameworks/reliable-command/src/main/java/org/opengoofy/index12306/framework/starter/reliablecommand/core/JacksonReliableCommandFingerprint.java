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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 使用字段有序 JSON 和 SHA-256 计算稳定请求摘要。
 */
public final class JacksonReliableCommandFingerprint implements ReliableCommandFingerprint {

    private final ObjectMapper canonicalMapper;

    /**
     * 基于应用已有的 Jackson 配置创建独立规范化序列化器。
     *
     * @param objectMapper 应用 ObjectMapper
     */
    public JacksonReliableCommandFingerprint(ObjectMapper objectMapper) {
        // 使用副本避免修改应用全局 JSON 输出顺序，同时保留业务注册的日期等序列化模块。
        this.canonicalMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.canonicalMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        this.canonicalMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 将摘要载荷规范化为 JSON 后计算 SHA-256。
     *
     * @param payload Descriptor 选择的摘要载荷
     * @return 小写十六进制 SHA-256 摘要
     */
    @Override
    public String calculate(Object payload) {
        try {
            // 先固定对象属性和 Map 键顺序，再计算摘要，避免等价请求因构造顺序不同产生新命令。
            byte[] canonicalJson = canonicalMapper.writeValueAsBytes(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Reliable command fingerprint payload cannot be serialized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
