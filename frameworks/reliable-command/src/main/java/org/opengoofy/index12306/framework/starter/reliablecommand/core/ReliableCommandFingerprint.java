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
 * 将业务选择的请求字段转换为稳定摘要。
 */
@FunctionalInterface
public interface ReliableCommandFingerprint {

    /**
     * 计算规范化请求摘要。
     *
     * @param payload Descriptor 选择的摘要载荷
     * @return 可持久化比较的摘要文本
     */
    String calculate(Object payload);
}
