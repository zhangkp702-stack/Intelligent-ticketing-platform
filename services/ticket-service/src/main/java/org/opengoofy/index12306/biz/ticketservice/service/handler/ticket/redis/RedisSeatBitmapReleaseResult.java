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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis;

/**
 * Redis 临时座位位图释放结果。
 */
public enum RedisSeatBitmapReleaseResult {

    /** 当前 reservation 释放了自己的位图，或这些位图已被本 reservation 释放。 */
    RELEASED,

    /** 当前位图已经属于新的 reservation，旧任务不得继续清理。 */
    OWNER_CHANGED
}
