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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 使用外部 Base64 AES 密钥保护 Relay 重试载荷的实现。
 *
 * <p>每条记录使用随机 IV；密文格式为 Base64(IV + ciphertext + authentication tag)。</p>
 */
public class AesGcmCanalRelayPayloadProtector implements CanalRelayPayloadProtector {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String encodedKey;
    private final int keyVersion;

    /**
     * 创建 AES-GCM 载荷保护器。
     *
     * @param encodedKey 外部注入的 Base64 AES 密钥
     * @param keyVersion 当前密钥版本
     */
    public AesGcmCanalRelayPayloadProtector(String encodedKey, int keyVersion) {
        this.encodedKey = encodedKey;
        this.keyVersion = keyVersion;
    }

    /**
     * 对明文载荷执行带认证标签的 AES-GCM 加密。
     *
     * @param payload 待保护的明文载荷
     * @return Base64 编码的 IV 和密文
     */
    @Override
    public String protect(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Relay 重试载荷不能为空");
        }
        byte[] iv = new byte[IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        try {
            // GCM 同时提供机密性和完整性，避免重试载荷被静默篡改。
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Relay 重试载荷加密失败", exception);
        }
    }

    /**
     * 校验密钥版本后解密数据库中的重试载荷。
     *
     * @param ciphertext Base64 编码的 IV 和密文
     * @param keyVersion 数据记录中的密钥版本
     * @return 解密后的明文载荷
     */
    @Override
    public String restore(String ciphertext, int keyVersion) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("Relay 重试密文不能为空");
        }
        if (keyVersion != this.keyVersion) {
            throw new IllegalStateException("Relay 重试载荷密钥版本不受支持");
        }
        try {
            byte[] encoded = Base64.getDecoder().decode(ciphertext);
            if (encoded.length <= IV_LENGTH) {
                throw new IllegalArgumentException("Relay 重试密文格式不合法");
            }
            byte[] iv = Arrays.copyOf(encoded, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(encoded, IV_LENGTH, encoded.length);
            // GCM 认证失败时直接拒绝恢复，不能把篡改内容投递到 MQ。
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Relay 重试密文格式不合法", exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Relay 重试载荷解密失败", exception);
        }
    }

    /**
     * 返回当前密钥版本，供持久化记录保存密钥轮换信息。
     *
     * @return 当前密钥版本
     */
    @Override
    public int currentKeyVersion() {
        return keyVersion;
    }

    /**
     * 创建指定模式的 AES-GCM Cipher，并在首次使用时校验外部密钥。
     *
     * @param mode 加密或解密模式
     * @param iv 当前记录随机 IV
     * @return 已初始化 Cipher
     * @throws GeneralSecurityException 密钥或算法配置非法
     */
    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException("Relay 重试载荷加密密钥未配置");
        }
        byte[] key = Base64.getDecoder().decode(encodedKey);
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalArgumentException("Relay AES 密钥必须为 16、24 或 32 字节");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher;
    }
}
