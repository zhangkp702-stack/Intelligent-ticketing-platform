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

package org.opengoofy.index12306.framework.starter.reliablecommand.event;

/**
 * @param key 事件主键
 * @param deduplicationKey 业务去重键
 * @param eventType 稳定事件类型
 * @param aggregateId 业务聚合标识
 * @param payload 不可变安全载荷
 * @param eventVersion 事件协议版本
 */
public record ReliableEventDefinition(
        ReliableEventKey key,
        String deduplicationKey,
        String eventType,
        String aggregateId,
        String payload,
        long eventVersion) {
}
