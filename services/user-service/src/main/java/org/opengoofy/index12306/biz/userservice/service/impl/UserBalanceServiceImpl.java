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

package org.opengoofy.index12306.biz.userservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.userservice.dao.entity.UserBalanceFlowDO;
import org.opengoofy.index12306.biz.userservice.dao.mapper.UserBalanceFlowMapper;
import org.opengoofy.index12306.biz.userservice.dao.mapper.UserMapper;
import org.opengoofy.index12306.biz.userservice.dto.req.BalanceChangeReqDTO;
import org.opengoofy.index12306.biz.userservice.dto.resp.UserBalanceRespDTO;
import org.opengoofy.index12306.biz.userservice.service.UserBalanceService;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户余额服务实现。
 */
@Service
@RequiredArgsConstructor
public class UserBalanceServiceImpl implements UserBalanceService {

    private static final int BIZ_TYPE_DEBIT = 0;
    private static final int BIZ_TYPE_CREDIT = 1;
    private static final int FLOW_STATUS_SUCCESS = 1;

    private final UserMapper userMapper;
    private final UserBalanceFlowMapper balanceFlowMapper;

    /**
     * 查询当前登录用户的可用余额。
     *
     * @return 当前余额
     */
    @Override
    public UserBalanceRespDTO queryBalance() {
        // 用户名来自网关建立的可信上下文，用于命中正确的用户分片。
        String username = requireUsername();
        Long balance = userMapper.selectBalance(username);
        if (balance == null) {
            throw new ClientException("用户不存在");
        }
        return new UserBalanceRespDTO(balance);
    }

    /**
     * 在本地事务中幂等扣减当前用户余额。
     *
     * @param requestParam 扣款业务与金额
     * @return 扣款后的余额
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserBalanceRespDTO debit(BalanceChangeReqDTO requestParam) {
        // 同一业务号先占用流水，再锁定用户余额，避免重复点击和并发支付重复扣款。
        return changeBalance(requestParam, BIZ_TYPE_DEBIT, false);
    }

    /**
     * 在本地事务中幂等退回当前用户余额。
     *
     * @param requestParam 退款业务与金额
     * @return 退款后的余额
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserBalanceRespDTO credit(BalanceChangeReqDTO requestParam) {
        // 退款和支付使用不同业务类型，同一退款请求号只能入账一次。
        return changeBalance(requestParam, BIZ_TYPE_CREDIT, true);
    }

    /**
     * 执行一次受流水幂等保护的余额变动。
     *
     * @param requestParam 业务号与金额
     * @param bizType 余额流水类型
     * @param credit 是否增加余额
     * @return 变动后的余额
     */
    private UserBalanceRespDTO changeBalance(
            BalanceChangeReqDTO requestParam,
            int bizType,
            boolean credit) {
        String username = requireUsername();

        // INSERT IGNORE 利用唯一索引原子取得处理权，已处理请求直接复用原结果。
        int inserted = balanceFlowMapper.insertProcessing(
                username, requestParam.getBizNo(), bizType, requestParam.getAmount());
        if (inserted == 0) {
            UserBalanceFlowDO existing = findFlow(username, requestParam.getBizNo(), bizType);
            if (existing == null || existing.getStatus() == null
                    || existing.getStatus() != FLOW_STATUS_SUCCESS) {
                throw new ServiceException("余额变动正在处理中，请稍后重试");
            }
            return new UserBalanceRespDTO(existing.getBalanceAfter());
        }

        // 行锁保证同一用户的多笔支付和退款按顺序计算余额。
        Long balanceBefore = userMapper.selectBalanceForUpdate(username);
        if (balanceBefore == null) {
            throw new ClientException("用户不存在");
        }
        if (!credit && balanceBefore < requestParam.getAmount()) {
            throw new ClientException("账户余额不足");
        }

        // 余额更新和流水完成处于同一用户库事务，任一步失败都会整体回滚。
        int changed = credit
                ? userMapper.creditBalance(username, requestParam.getAmount())
                : userMapper.debitBalance(username, requestParam.getAmount());
        if (changed != 1) {
            throw new ServiceException(credit ? "余额退款失败" : "余额扣款失败");
        }
        long balanceAfter = credit
                ? balanceBefore + requestParam.getAmount()
                : balanceBefore - requestParam.getAmount();
        int flowUpdated = balanceFlowMapper.markSuccess(
                username, requestParam.getBizNo(), bizType, balanceBefore, balanceAfter);
        if (flowUpdated != 1) {
            throw new ServiceException("余额流水更新失败");
        }
        return new UserBalanceRespDTO(balanceAfter);
    }

    /**
     * 查询指定用户和业务号的余额流水。
     *
     * @param username 用户名
     * @param bizNo 业务幂等号
     * @param bizType 业务类型
     * @return 已存在的余额流水
     */
    private UserBalanceFlowDO findFlow(String username, String bizNo, int bizType) {
        // 查询携带 username 分片键，避免余额流水广播到全部用户分片。
        return balanceFlowMapper.selectOne(Wrappers.lambdaQuery(UserBalanceFlowDO.class)
                .eq(UserBalanceFlowDO::getUsername, username)
                .eq(UserBalanceFlowDO::getBizNo, bizNo)
                .eq(UserBalanceFlowDO::getBizType, bizType));
    }

    /**
     * 获取请求上下文中的可信用户名。
     *
     * @return 当前用户名
     */
    private String requireUsername() {
        // 余额接口禁止接受请求体中的用户名，避免为其他账户扣款或充值。
        String username = UserContext.getUsername();
        if (username == null || username.isBlank()) {
            throw new ClientException("用户未登录");
        }
        return username;
    }
}
