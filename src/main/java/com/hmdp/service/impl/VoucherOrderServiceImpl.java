package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.val;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    //Redisson的锁前缀
    private static final String REDISSON_LOCK_ORDER_PREFIX="redissonLock::order";
    private static final String ORDER_PREFIX="order";
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    //利用redisson的锁
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisWorker redisWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisTemplate redisTemplate;
    private static final DefaultRedisScript<Long> SEC_KILL_SCRIPT;

    private static final ExecutorService SEC_KILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    static {
        SEC_KILL_SCRIPT=new DefaultRedisScript<>();
        SEC_KILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SEC_KILL_SCRIPT.setResultType(Long.class);
    }
    //异步秒杀方案，把秒杀卷信息放在redis，秒杀资格判断放在redis，由于
    // redisLua脚本有原子性的特性，所以扣减库存和资格的判断是同时完成的，不存在幻读的问题

    @PostConstruct
    private void init(){
        SEC_KILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());

    }
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1、获取消息队列中的信息 XREAD GROUP g1 c1 count 1 block 2000 streams stream.order >
                    val res = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.order", ReadOffset.lastConsumed())
                    );
                    //2、判断消息获取是否成功，如果失败，继续下一次循环
                    if (res == null || res.isEmpty()) continue;
                    //如果有消息，就要去下单
                    val entries = res.get(0);
                    val map = entries.getValue();
                    val voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    //2、创建订单,扣减库存
                    handleOrder(voucherOrder);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge("stream.order", "g1", entries.getId());
                }catch (Exception e){
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //1、获取pendingList中的信息 XREAD GROUP g1 c1 count 1 block 2000 streams stream.order 0
                    val res = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.order", ReadOffset.from("0"))
                    );
                    //2、判断消息获取是否成功，如果失败,说明没有pedinglist，结束循环
                    if (res == null || res.isEmpty()) break;
                    //如果有消息，就要去下单
                    val entries = res.get(0);
                    val map = entries.getValue();
                    val voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    //2、创建订单,扣减库存
                    handleOrder(voucherOrder);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge("stream.order", "g1", entries.getId());
                }catch (Exception e){
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleOrder(VoucherOrder voucherOrder){
        //扣减库存
            iSeckillVoucherService.update().setSql("stock=stock-1")
                    .eq("voucher_id",voucherOrder.getVoucherId()).gt("stock",0).update();
        //创建订单
            save(voucherOrder);
    }
    @Override
    public Result seckillVoucher(Long voucherId) {

        final Long id = UserHolder.getUser().getId();
        //获取订单id
        final long orderId = redisWorker.nextId(ORDER_PREFIX);
        Long res= stringRedisTemplate.execute(
                SEC_KILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                id.toString(),
                String.valueOf(orderId)
        );
        //返回结果不为0，说明没资格
        if(res!=null&&res.intValue()!=0){
            int code=res.intValue();
            if(code==1)return Result.fail("库存不足");
            if(code==2)return Result.fail("不能重复下单");
            if(code==3)return Result.fail("优惠卷不存在");
        }
        return Result.ok(orderId);
    }





    //这个是串行执行的秒杀方案
//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//
//
//
//        //1、查询有没有该优惠卷
//        SeckillVoucher seckillVoucher;
//        if((seckillVoucher=iSeckillVoucherService.getById(voucherId))==null){
//                return Result.fail("优惠卷id不合法");
//        }
//        //判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        //2、查询库存
//        if (seckillVoucher.getStock()<1) {
//            return Result.fail("库存不足");
//        }
//        //查询是否有订单，解决可能会出现幻读
//        //.last("for update") 1、锁定读
//        //2、redis分布式锁,先用redis
//
//        //获取锁,这种是自己写的锁，并发程度高还是会出现超卖的问题，原因应该是没锁柱，过期时间的问题
////        val id = UserHolder.getUser().getId();
////        RedisLock lock=new RedisLock("order:"+id,stringRedisTemplate);
////        if (!lock.tryLock(1200)) {
////            return Result.fail("不允许重复下单");
////        }
//        //获取锁，这个是redisson提供的分布式锁,他的锁就能锁住了，也不用锁定读了
//        //tryLock有三个参数，一个是最大等待时间，一个是最大释放时间
//        //1、创建锁对象
//        RLock lock= redissonClient.getLock(REDISSON_LOCK_ORDER_PREFIX+UserHolder.getUser().getId());
//        if (!lock.tryLock()) {
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            //redis分布式锁查询个人抢购次数要放在这里，double判断因为就算获取锁成功只后，数据库中可能已经存在了他的数据。
//            if(query()
//                    .eq("user_id", UserHolder.getUser().getId())
//                    .eq("voucher_id", voucherId)
//                    .last("for update")
//                    .count() >0)
//                return Result.fail("你已抢购过了");
//            //3、扣除库存，创建订单
//            //4、运用简单的乐观锁解决超卖问题，但是还是不安全的，应该在该字段后面加上一个版本号或者时间戳
//            if (!iSeckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id",voucherId).
//                    gt("stock",0).update()) {
//                return Result.fail("系统繁忙，请耐心等待");
//            }
//            VoucherOrder voucherOrder=new VoucherOrder();
//            //生成唯一订单id
//            final Long orderId = redisWorker.nextId(ORDER_PREFIX);
//            //设置订单id
//            voucherOrder.setId(orderId);
//            //直接发postman调用会有NULLPOINTER异常，设置userid
//            voucherOrder.setUserId(UserHolder.getUser().getId());
//            voucherOrder.setVoucherId(voucherId);
//            //保存数据
//            save(voucherOrder);
//            //4、返回订单id
//            return Result.ok(orderId);
//        }finally {
//            lock.unlock();
//        }
//    }
}
