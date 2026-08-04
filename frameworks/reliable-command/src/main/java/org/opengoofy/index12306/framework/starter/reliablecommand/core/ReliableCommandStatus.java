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

/**
 * 可靠命令的持久化执行状态。
 */
public enum ReliableCommandStatus {

    /** 正在持有执行租约并执行真实业务。 */
    PROCESSING,
    /** 已确认成功，可以向重复请求复用持久化结果。 */
    SUCCEEDED,
    /** 已确认失败，只有新的业务命令才可以再次执行。 */
    FAILED,
    /** 无法判断远程副作用是否成功，只允许执行权威状态查询。 */
    UNKNOWN,
    /** 某个实例已经领取只读对账任务。 */
    RECONCILING,
    /** 自动对账无法得到结论，需要受控的人工处理。 */
    MANUAL_REVIEW
}
