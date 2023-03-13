package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IVoucherOrderService voucherOrderService;

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                  VoucherOrder voucherOrder = orderTasks.take();
//                  handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果失败则没有消息，继续下一次重试
                        continue;
                    }

                    // 解析订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 如果成功则可以下单
                    handleVoucherOrder(voucherOrder);

                    // 进行ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 获取消息队列中的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果失败则penDingList没有消息，结束
                        break;
                    }

                    // 解析订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 如果成功则可以下单
                    handleVoucherOrder(voucherOrder);

                    // 进行ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理penDingList异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order" + userId);
        boolean isLock = lock.tryLock();

        // 判断是否获取锁成功
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }

        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
}

    /**
     * 执行lua脚本 将订单存入消息队列
     * @param voucherId 优惠券id
     * @return 是否成功
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // 获取用户ID
        Long userId = UserHolder.getUser().getId();

        // 获取订单ID
        Long orderId = RedisIdWorker.nextId("order");

        log.warn(voucherId.toString()+ "   " + userId.toString()+ "   " +String.valueOf(orderId));
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        int r = result.intValue();
        if (r != 0) {
            // 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }


        // 返回
        return Result.ok(orderId);
    }


//    /**
//     * 执行lua脚本 将订单存入阻塞队列
//     * @param voucherId 优惠券id
//     * @return 是否成功
//     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
////        执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//
//        int r = result.intValue();
//        if (r != 0) {
////            不为0，代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//
////        为0，把下单信息保存到阻塞队列
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        VoucherOrder voucherOrder = new VoucherOrder();
//        Long orderId = RedisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(userId);
//
//        orderTasks.add(voucherOrder);
//
////        返回
//        return Result.ok(orderId);
//    }

//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        // 查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//
//        if (seckillVoucher == null) {
//            return Result.fail("查询不到秒杀券信息");
//        }
//
//        // 判断秒杀是否开始或结束
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀还未开始!");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束!");
//        }
//
//        // 判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("优惠券已经被抢完啦!");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        // 获取锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("user:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//        boolean isLock = lock.tryLock();
//
//        // 判断是否获取锁成功
//        if (!isLock) {
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    /**
     * 在mysql中创建订单
     * @param voucherOrder 优惠券订单
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .update();

        // 创建订单
        save(voucherOrder);
    }
}
