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

package org.opengoofy.index12306.biz.orderservice.common.enums;

/**
 * 订单创建 Command 的持久化状态。
 *
 * <p>只有 {@link #FAILED} 表示已经确认且允许通知票务服务释放座位；
 * 处理中、等待重试和结果未知均不得触发座位补偿。</p>
 */
public enum OrderCommandStatusEnum {

    /** 已可靠接收，等待执行器认领。 */
    RECEIVED,

    /** 已被一个执行器持租约认领。 */
    PROCESSING,

    /** 本次执行失败，等待下一次重试。 */
    RETRY_WAIT,

    /** 执行结果暂时无法确认，需要先与订单数据对账。 */
    UNKNOWN,

    /** 订单、Command 终态和结果 Outbox 已在同一事务提交。 */
    SUCCEEDED,

    /** 已确认不可重试，并已持久化失败结果。 */
    FAILED;

    /**
     * 判断当前状态是否不会再由执行器重试。
     *
     * @return 成功或最终失败时返回 true
     */
    public boolean isTerminal() {
        // 终态一旦写入，只允许幂等读取，不再被普通执行器重新认领。
        return this == SUCCEEDED || this == FAILED;
    }

    /**
     * 判断当前状态是否允许票务侧进入座位释放流程。
     *
     * @return 仅最终失败状态返回 true
     */
    public boolean allowsSeatRelease() {
        // UNKNOWN 可能已经产生订单，不能按失败处理。
        return this == FAILED;
    }
}
