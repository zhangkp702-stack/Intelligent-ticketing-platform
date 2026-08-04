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
 * 可靠命令的副作用执行模式，用于约束事务边界和异常后的恢复方式。
 */
public enum ReliableCommandMode {

    /**
     * 命令记录和业务数据写入同一数据库事务，事务回滚时两者必须一起回滚。
     */
    LOCAL_ATOMIC,

    /**
     * 命令会调用无法纳入本地事务的远程副作用，超时后必须进入未知状态并对账。
     */
    REMOTE_EFFECT,

    /**
     * 命令来自至少一次投递的消息，使用消费者命名空间和消息标识抑制重复消费。
     */
    INBOX_CONSUME
}
