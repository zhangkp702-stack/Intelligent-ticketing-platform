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

package org.opengoofy.index12306.biz.userservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.opengoofy.index12306.biz.userservice.dao.entity.UserDO;

/**
 * 用户信息持久层
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public interface UserMapper extends BaseMapper<UserDO> {

    /**
     * 查询当前用户余额。
     *
     * @param username 用户名
     * @return 当前余额，单位为分
     */
    @Select("SELECT balance FROM t_user WHERE username = #{username} AND del_flag = 0")
    Long selectBalance(@Param("username") String username);

    /**
     * 锁定当前用户并读取余额，确保并发支付按用户串行扣款。
     *
     * @param username 用户名
     * @return 当前余额，单位为分
     */
    @Select("SELECT balance FROM t_user WHERE username = #{username} AND del_flag = 0 FOR UPDATE")
    Long selectBalanceForUpdate(@Param("username") String username);

    /**
     * 扣减用户余额。
     *
     * @param username 用户名
     * @param amount 扣减金额，单位为分
     * @return 更新记录数
     */
    @Update("UPDATE t_user SET balance = balance - #{amount}, update_time = NOW() "
            + "WHERE username = #{username} AND balance >= #{amount} AND del_flag = 0")
    int debitBalance(@Param("username") String username, @Param("amount") Long amount);

    /**
     * 增加用户余额。
     *
     * @param username 用户名
     * @param amount 增加金额，单位为分
     * @return 更新记录数
     */
    @Update("UPDATE t_user SET balance = balance + #{amount}, update_time = NOW() "
            + "WHERE username = #{username} AND del_flag = 0")
    int creditBalance(@Param("username") String username, @Param("amount") Long amount);

    /**
     * 注销用户
     *
     * @param userDO 注销用户入参
     */
    void deletionUser(UserDO userDO);
}
