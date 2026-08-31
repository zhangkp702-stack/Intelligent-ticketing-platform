-- Canal Relay 第二阶段回滚脚本。
-- 仅在确认已停止 Relay、已导出 RETRY/DEAD 任务并获得变更审批后执行。

USE `12306_canal_relay`;
DROP TABLE IF EXISTS `t_canal_relay_retry`;
