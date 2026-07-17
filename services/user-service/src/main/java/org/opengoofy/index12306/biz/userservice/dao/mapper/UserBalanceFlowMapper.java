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
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.opengoofy.index12306.biz.userservice.dao.entity.UserBalanceFlowDO;

/**
 * 用户余额流水持久层。
 */
public interface UserBalanceFlowMapper extends BaseMapper<UserBalanceFlowDO> {

    /**
     * 尝试占用一次余额变动业务，唯一索引保证同一业务只能成功一次。
     *
     * @param username 用户名
     * @param bizNo 业务幂等号
     * @param bizType 业务类型
     * @param amount 变动金额
     * @return 插入成功时返回 1，业务已存在时返回 0
     */
    @Insert("INSERT IGNORE INTO t_user_balance_flow "
            + "(username, biz_no, biz_type, amount, status, create_time, update_time, del_flag) "
            + "VALUES (#{username}, #{bizNo}, #{bizType}, #{amount}, 0, NOW(), NOW(), 0)")
    int insertProcessing(
            @Param("username") String username,
            @Param("bizNo") String bizNo,
            @Param("bizType") Integer bizType,
            @Param("amount") Long amount);

    /**
     * 将已完成的余额变动更新为成功并保存变动前后余额。
     *
     * @param username 用户名
     * @param bizNo 业务幂等号
     * @param bizType 业务类型
     * @param balanceBefore 变动前余额
     * @param balanceAfter 变动后余额
     * @return 更新记录数
     */
    @Update("UPDATE t_user_balance_flow "
            + "SET balance_before = #{balanceBefore}, balance_after = #{balanceAfter}, "
            + "status = 1, update_time = NOW() "
            + "WHERE username = #{username} AND biz_no = #{bizNo} "
            + "AND biz_type = #{bizType} AND del_flag = 0")
    int markSuccess(
            @Param("username") String username,
            @Param("bizNo") String bizNo,
            @Param("bizType") Integer bizType,
            @Param("balanceBefore") Long balanceBefore,
            @Param("balanceAfter") Long balanceAfter);
}
