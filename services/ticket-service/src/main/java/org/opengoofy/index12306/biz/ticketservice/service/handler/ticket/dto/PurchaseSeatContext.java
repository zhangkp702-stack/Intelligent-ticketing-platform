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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto;

import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;

import java.util.Map;

/**
 * 锁座前准备完成的乘车人和席别价格快照。
 *
 * @param passengerById 当前用户的乘车人权威快照
 * @param priceBySeatType 当前车次区间的席别价格
 */
public record PurchaseSeatContext(Map<String, PassengerRespDTO> passengerById,
                                  Map<Integer, Integer> priceBySeatType) {
}
