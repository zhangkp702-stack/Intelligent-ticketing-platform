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

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 可靠命令允许的状态迁移规则。
 */
public final class ReliableCommandStateMachine {

    private static final Map<ReliableCommandStatus, EnumSet<ReliableCommandStatus>> TRANSITIONS =
            new EnumMap<>(ReliableCommandStatus.class);

    static {
        TRANSITIONS.put(ReliableCommandStatus.PROCESSING, EnumSet.of(
                ReliableCommandStatus.SUCCEEDED,
                ReliableCommandStatus.FAILED,
                ReliableCommandStatus.UNKNOWN));
        TRANSITIONS.put(ReliableCommandStatus.UNKNOWN, EnumSet.of(
                ReliableCommandStatus.RECONCILING,
                ReliableCommandStatus.MANUAL_REVIEW));
        TRANSITIONS.put(ReliableCommandStatus.RECONCILING, EnumSet.of(
                ReliableCommandStatus.SUCCEEDED,
                ReliableCommandStatus.FAILED,
                ReliableCommandStatus.UNKNOWN,
                ReliableCommandStatus.MANUAL_REVIEW));
        TRANSITIONS.put(ReliableCommandStatus.SUCCEEDED, EnumSet.noneOf(ReliableCommandStatus.class));
        TRANSITIONS.put(ReliableCommandStatus.FAILED, EnumSet.noneOf(ReliableCommandStatus.class));
        TRANSITIONS.put(ReliableCommandStatus.MANUAL_REVIEW, EnumSet.of(ReliableCommandStatus.UNKNOWN));
    }

    /**
     * 工具类不允许实例化。
     */
    private ReliableCommandStateMachine() {
    }

    /**
     * 判断一个状态迁移是否符合可靠命令恢复约束。
     *
     * @param current 当前状态
     * @param target 目标状态
     * @return 允许迁移返回 true
     */
    public static boolean canTransition(ReliableCommandStatus current, ReliableCommandStatus target) {
        if (current == null || target == null) {
            return false;
        }
        return TRANSITIONS.get(current).contains(target);
    }

    /**
     * 校验状态迁移，防止调用方绕过未知状态和人工处理边界。
     *
     * @param current 当前状态
     * @param target 目标状态
     */
    public static void requireTransition(ReliableCommandStatus current, ReliableCommandStatus target) {
        if (!canTransition(current, target)) {
            throw new IllegalStateException("Illegal reliable command transition: " + current + " -> " + target);
        }
    }
}
