package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Author: ziluxike
 * Time: 2023/2/24 12:37
 */
@Component
@Slf4j
public class RedisClient {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     * @param id 数据id
     * @param type 数据类型
     * @param keyPrefix key前缀
     * @param fbFallback 查询数据函数
     * @param time TTL过期时间
     * @param unit TLL过期时间单位
     * @return 返回数据
     * @param <R> 数据
     * @param <ID> id
     */
    public <R, ID> R queryWithPassThrough(
            ID id, Class<R> type, String keyPrefix, Function<ID, R> fbFallback, Long time, TimeUnit unit
    ) {
        // 1.从redis查询商户信息
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在直接返回
            return JSONUtil.toBean(json, type);
        }

        // 判断是否为空值
        if (json != null) {
            return null;
        }

        // 4.不存在，从mysql中查询
        R r = fbFallback.apply(id);

        // 5.不存在，返回错误
        if (BeanUtil.isEmpty(r)) {
            this.set(keyPrefix + id, "", time, unit);
            return null;
        }

        // 6.存在，写入redis
        this.set(keyPrefix + id, r, time, unit);

        // 7.返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(
            ID id,
            Class<R> type,
            String keyPrefix,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;

        // 1.从redis查询商户信息
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 不存在 返回null
            return null;
        }

        // 从redis获取数据
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 如果过期时间在当前时间之后
        if (expireTime.isAfter(LocalDateTime.now())) {
//            没有过期
            return r;
        }

        // 过期则重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 返回
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
