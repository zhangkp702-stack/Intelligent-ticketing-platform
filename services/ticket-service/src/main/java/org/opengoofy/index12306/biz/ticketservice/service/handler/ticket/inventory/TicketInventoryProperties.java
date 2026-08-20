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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.inventory;

import lombok.Getter;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketInventoryModeEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一余票库存迁移配置。
 */
@Getter
@Component
public class TicketInventoryProperties {

    private final TicketInventoryModeEnum mode;

    /**
     * 读取库存迁移模式，默认保持旧链路运行。
     *
     * @param mode 配置的 legacy、shadow 或 unified 模式
     */
    public TicketInventoryProperties(@Value("${ticket.inventory.mode:legacy}") String mode) {
        // 在组件初始化时完成模式校验，避免运行期静默接受错误配置。
        this.mode = TicketInventoryModeEnum.from(mode);
    }
}
