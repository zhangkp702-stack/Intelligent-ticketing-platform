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

package org.opengoofy.index12306.biz.userservice.service;

import org.opengoofy.index12306.biz.userservice.dto.req.BalanceChangeReqDTO;
import org.opengoofy.index12306.biz.userservice.dto.resp.UserBalanceRespDTO;

/**
 * 用户余额服务。
 */
public interface UserBalanceService {

    /**
     * 查询当前登录用户余额。
     *
     * @return 当前余额
     */
    UserBalanceRespDTO queryBalance();

    /**
     * 按业务幂等号扣减当前用户余额。
     *
     * @param requestParam 扣款业务与金额
     * @return 扣款后的余额
     */
    UserBalanceRespDTO debit(BalanceChangeReqDTO requestParam);

    /**
     * 按业务幂等号退回当前用户余额。
     *
     * @param requestParam 退款业务与金额
     * @return 退款后的余额
     */
    UserBalanceRespDTO credit(BalanceChangeReqDTO requestParam);
}
