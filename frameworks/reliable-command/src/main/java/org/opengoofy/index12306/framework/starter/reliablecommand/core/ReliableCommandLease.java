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

import java.time.Instant;
import java.util.Objects;

/**
 * 某个实例当前持有的数据库执行权。
 *
 * @param owner 执行实例标识
 * @param until 租约截止时间
 * @param fencingToken 单调递增的围栏令牌
 */
public record ReliableCommandLease(String owner, Instant until, long fencingToken) {

    /**
     * 校验租约可以安全参与状态更新条件。
     *
     * @param owner 执行实例标识
     * @param until 租约截止时间
     * @param fencingToken 围栏令牌
     */
    public ReliableCommandLease {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("lease owner must not be blank");
        }
        owner = owner.trim();
        if (owner.length() > 128) {
            throw new IllegalArgumentException("lease owner exceeds 128 characters");
        }
        until = Objects.requireNonNull(until, "until");
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }
}
