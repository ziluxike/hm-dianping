package com.hmdp.utils;

/**
 * Author: ziluxike
 * Time: 2023/3/2 20:03
 */
public interface ILcok {

    /**
     * 尝试获取锁
     * @param timeoutSec 过期时间
     * @return 是否成功
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
