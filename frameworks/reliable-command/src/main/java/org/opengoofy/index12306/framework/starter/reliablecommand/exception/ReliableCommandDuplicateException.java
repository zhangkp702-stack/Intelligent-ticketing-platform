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

package org.opengoofy.index12306.framework.starter.reliablecommand.exception;

import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;

import java.util.Objects;

/**
 * 可靠命令重复请求无法安全重放时抛出的状态异常。
 */
public final class ReliableCommandDuplicateException extends IllegalStateException {

    private final Reason reason;
    private final ReliableCommandKey key;

    /**
     * 创建包含稳定原因和命令键的异常。
     *
     * @param reason 当前不可重放原因
     * @param key 触发判定的命令键
     * @param message 面向调用方的安全错误摘要
     */
    private ReliableCommandDuplicateException(Reason reason, ReliableCommandKey key, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.key = Objects.requireNonNull(key, "key");
    }

    /**
     * 创建命令仍在执行中的异常。
     *
     * @param key 当前命令键
     * @return 正在处理异常
     */
    public static ReliableCommandDuplicateException processing(ReliableCommandKey key) {
        return new ReliableCommandDuplicateException(Reason.PROCESSING, key, "Reliable command is still processing");
    }

    /**
     * 创建同一命令标识绑定不同请求的异常。
     *
     * @param key 当前命令键
     * @return 请求摘要不一致异常
     */
    public static ReliableCommandDuplicateException payloadMismatch(ReliableCommandKey key) {
        return new ReliableCommandDuplicateException(Reason.PAYLOAD_MISMATCH, key, "Reliable command payload mismatches");
    }

    /**
     * 创建不同主体复用命令标识的异常。
     *
     * @param key 当前命令键
     * @return 命令所属主体不一致异常
     */
    public static ReliableCommandDuplicateException ownerMismatch(ReliableCommandKey key) {
        return new ReliableCommandDuplicateException(Reason.OWNER_MISMATCH, key, "Reliable command owner mismatches");
    }

    /**
     * 创建已确定失败命令的异常。
     *
     * @param key 当前命令键
     * @param detail 已持久化失败摘要
     * @return 终态失败异常
     */
    public static ReliableCommandDuplicateException terminalFailure(ReliableCommandKey key, String detail) {
        return new ReliableCommandDuplicateException(Reason.TERMINAL_FAILURE, key,
                "Reliable command has terminal failure" + detailSuffix(detail));
    }

    /**
     * 创建结果未知且必须对账的异常。
     *
     * @param key 当前命令键
     * @param detail 已持久化未知摘要
     * @return 结果未知异常
     */
    public static ReliableCommandDuplicateException resultUncertain(ReliableCommandKey key, String detail) {
        return new ReliableCommandDuplicateException(Reason.RESULT_UNCERTAIN, key,
                "Reliable command result is uncertain" + detailSuffix(detail));
    }

    /**
     * 返回当前异常的稳定原因，供 Web 层映射为冲突或待对账响应。
     *
     * @return 不可重放原因
     */
    public Reason reason() {
        return reason;
    }

    /**
     * 返回触发异常的稳定命令键。
     *
     * @return 命令键
     */
    public ReliableCommandKey key() {
        return key;
    }

    /**
     * 拼接不为空的已持久化异常摘要。
     *
     * @param detail 原始异常摘要
     * @return 安全消息后缀
     */
    private static String detailSuffix(String detail) {
        return detail == null || detail.isBlank() ? "" : ": " + detail.trim();
    }

    /**
     * 可靠命令无法重放的稳定分类。
     */
    public enum Reason {
        /** 同一命令仍由其他实例执行。 */
        PROCESSING,
        /** 同一命令标识对应的请求摘要不同。 */
        PAYLOAD_MISMATCH,
        /** 同一命令标识对应的所属主体不同。 */
        OWNER_MISMATCH,
        /** 命令已经获得可确认的失败终态。 */
        TERMINAL_FAILURE,
        /** 下游副作用无法确认，只能等待对账或人工处理。 */
        RESULT_UNCERTAIN
    }
}
