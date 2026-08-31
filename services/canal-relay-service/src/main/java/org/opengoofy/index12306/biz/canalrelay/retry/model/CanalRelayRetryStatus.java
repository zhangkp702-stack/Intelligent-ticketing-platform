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

package org.opengoofy.index12306.biz.canalrelay.retry.model;

/**
 * Relay 单条失败任务的持久化状态。
 *
 * <p>第二阶段只登记待重试和人工隔离两种结果；发送中的租约状态由后续发布器阶段补充。</p>
 */
public enum CanalRelayRetryStatus {

    /** 允许后台发布器再次尝试。 */
    RETRY,

    /** 已超过自动重试边界或被判定为永久错误。 */
    DEAD
}
