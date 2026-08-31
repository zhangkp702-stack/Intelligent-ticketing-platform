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

package org.opengoofy.index12306.biz.canalrelay.retry.service;

import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryEvent;

import java.util.Collection;

/**
 * Relay 失败事件的持久化边界。
 */
public interface CanalRelayRetryEventStore {

    /**
     * 在一个本地事务内保存一批失败事件。
     *
     * <p>重复事件必须复用已有记录；如果不可变元数据或载荷摘要不一致，则整个事务失败。</p>
     *
     * @param events 当前批次需要隔离的失败事件
     */
    void saveAll(Collection<CanalRelayRetryEvent> events);
}
