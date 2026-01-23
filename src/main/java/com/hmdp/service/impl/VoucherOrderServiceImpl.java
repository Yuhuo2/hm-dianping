package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        // 先获取锁，提交事务，再释放索，避免并发问题
        Long userId = UserHolder.getUser().getId();

        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 获取锁
        boolean islock = lock.tryLock(1200);
        if (!islock) {
            // 获取锁失败，返回错误或者重试
            return Result.fail("不允许重复下单！");
        }

        //synchronized ： 基于这个字符串对象加锁，同一用户的并发请求会串行执行。
        try {
            //直接调用类内部的createVoucherOrder方法，会导致事务注解@Transactional失效，因为Spring的事务是通过代理对象来管理的
            //return this.createVoucherOrder(voucherId);

            //获取当前的代理对象，使用代理对象调用第三方事务方法，防止事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();   //获取当前类的代理对象
            return proxy.createVoucherOrder(voucherId);
        }finally {
            // 释放锁
            lock.unLock();
        }
    }

    /**
     * 通过数据库查询确保“一人一单”
     * @param voucherId
     * @return
     */
    @Transactional   // 事务注解：保证订单创建和库存扣减的原子性，并且只有事务提交后，其他请求才能看到新订单和库存变化
    public Result createVoucherOrder(Long voucherId) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();
        //5.1查询数据库中是否已经存在该用户抢购该优惠券的订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2判断是否存在
        if (count > 0) {
            //用户已经购买过了，返回失败信息
            return Result.fail("用户已购买！");
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")   //set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0)   //where id = ? and stock > 0 数据库层面的乐观锁，避免超卖
                .update();

        if (!success) {
            //库存扣减失败
            return Result.fail("库存不足");
        }

        //7.创建订单（在订单表tb_voucher_order插入一条数据）
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2 用户id
        voucherOrder.setUserId(userId);
        //7.3 代金券id
        voucherOrder.setVoucherId(voucherId);

        //插入到订单信息表
        save(voucherOrder);

        //8.返回订单id（生成唯一订单id并保存）
        return Result.ok(orderId);
    }
}
