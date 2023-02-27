package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class    ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisClient redisClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
//        Shop shop = redisClient.queryWithPassThrough
//                (1, Shop.class, RedisConstants.CACHE_SHOP_KEY, this::getById, 20L, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if (shop == null) {
//            return Result.fail("店铺不存在!");
//        }

        // 逻辑过期解决缓存击穿
        Shop shop = redisClient.queryWithLogicalExpire
                (id, Shop.class, RedisConstants.CACHE_SHOP_KEY,
                        this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
                );

        if (shop == null) {
            Result.fail("找不到该店铺!");
        }

        // 返回
        return Result.ok(shop);
    }

//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /*public Shop queryWithLogicalExpire(Long id) {
        // 1.从redis查询商户信息
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 不存在 返回null
            return null;
        }

        // 从redis获取数据
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 如果过期时间在当前时间之后
        if (expireTime.isAfter(LocalDateTime.now())) {
//            没有过期
            return shop;
        }

        // 过期则重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 返回
        return shop;
    }*/

    /*public Shop queryWithMutex(Long id) {
        // 1.从redis查询商户信息
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断是否为空值
        if (shopJson != null) {
            return null;
        }

        // 4.进行缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop;

        try {
            // 4.1 获取锁
            boolean isLock = tryLock(lockKey);

            // 4.2 获取不到 休眠重新获取数据
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 不存在，从mysql中查询
            shop = this.getById(id);
            // 模拟重建延迟
            Thread.sleep(200);

            // 5.不存在，返回错误
            if (BeanUtil.isEmpty(shop)) {
                // 将空值返回redis
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES
                );
                return null;
            }

            // 6.存在，写入redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                    RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
            );
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 6.释放互斥锁
            unlock(lockKey);
        }

        // 7.返回
        return shop;
    }*/


    /*public Shop queryWithPassThrough(Long id) {
        // 1.从redis查询商户信息
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在直接返回
            return BeanUtil.copyProperties(shopJson, Shop.class);
        }

        // 判断是否为空值
        if (shopJson != null) {
            return null;
        }

        // 4.不存在，从mysql中查询
        Shop shop = this.getById(id);

        // 5.不存在，返回错误
        if (BeanUtil.isEmpty(shop)) {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES
            );
            return null;
        }

        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
        );
        // 7.返回
        return shop;
    }
    */

    /*private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
     */

    /*private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }*/

    /**
     * 预热店铺
     * @param id 商铺id
     * @param expireSeconds 延长时间
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = this.getById(id);

        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("商铺id不能为空");
        }
        // 1.先更新数据库
        this.updateById(shop);
        // 2.后删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        // 3.返回
        return Result.ok();
    }
}
