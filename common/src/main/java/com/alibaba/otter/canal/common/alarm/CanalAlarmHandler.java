package com.alibaba.otter.canal.common.alarm;

import com.alibaba.otter.canal.common.CanalLifeCycle;

/**
 * canal报警处理机制
 * 
 * @author jianghang 2012-8-22 下午10:08:56
 * @version 4.1.0
 */
public interface CanalAlarmHandler extends CanalLifeCycle {

    /**
     * 发送对应destination的报警
     * 
     * @param destination
     * @param title
     * @param msg
     */
    public void sendAlarm(String destination, String msg);
}