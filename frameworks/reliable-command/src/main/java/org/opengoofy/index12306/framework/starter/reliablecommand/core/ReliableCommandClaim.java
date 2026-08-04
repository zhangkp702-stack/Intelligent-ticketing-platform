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
 * 命令认领或重复请求检查的结果。
 *
 * @param outcome 判定结果
 * @param record 当前权威命令记录
 */
public record ReliableCommandClaim(Outcome outcome, ReliableCommandRecord record) {

    /**
     * 校验认领结果始终携带可供上层判断的持久化记录。
     *
     * @param outcome 判定结果
     * @param record 当前命令记录
     */
    public ReliableCommandClaim {
        outcome = Objects.requireNonNull(outcome, "outcome");
        record = Objects.requireNonNull(record, "record");
    }

    /**
     * 判断当前调用是否获得真实业务执行权。
     *
     * @return 只有首次成功认领时返回 true
     */
    public boolean acquired() {
        return outcome == Outcome.ACQUIRED;
    }

    /**
     * 命令认领的稳定判定类型。
     */
    public enum Outcome {
        /** 首次认领成功，可以执行真实业务。 */
        ACQUIRED,
        /** 相同请求已经成功，应复用持久化结果。 */
        REPLAY_SUCCEEDED,
        /** 相同请求仍由某个实例处理，不允许并发执行。 */
        PROCESSING,
        /** 同一命令标识绑定了不同请求正文。 */
        PAYLOAD_MISMATCH,
        /** 同一命令标识属于另一个用户、租户或消费者。 */
        OWNER_MISMATCH,
        /** 命令已确定失败，不允许使用同一标识再次执行。 */
        TERMINAL_FAILURE,
        /** 命令结果未知、正在对账或等待人工处理。 */
        RESULT_UNCERTAIN
    }
}
