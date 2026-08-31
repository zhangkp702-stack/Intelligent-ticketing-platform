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

package org.opengoofy.index12306.biz.canalrelay.retry.security;

/**
 * Relay 重试载荷的加密与解密边界。
 */
public interface CanalRelayPayloadProtector {

    /**
     * 加密一条待重试消息载荷。
     *
     * @param payload 明文消息载荷
     * @return 可写入数据库的密文
     */
    String protect(String payload);

    /**
     * 解密一条重试消息载荷。
     *
     * @param ciphertext 数据库中的密文
     * @param keyVersion 加密时使用的密钥版本
     * @return 明文消息载荷
     */
    String restore(String ciphertext, int keyVersion);

    /**
     * 返回当前加密密钥版本。
     *
     * @return 密钥版本
     */
    int currentKeyVersion();
}
