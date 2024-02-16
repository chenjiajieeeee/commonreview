package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.common.NxLock.*;
import static com.hmdp.common.ShopCommon.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;
    @Override
    public Result queryShopById(Long id) {
        //调用以下方法解决缓存穿透、缓存击穿
        final Shop shop = cacheThroughAndMutex(id);
        if(shop==null)return Result.fail("店铺不存在");
        return Result.ok(shop);
        // 调用以下方法实现逻辑过期（这个是默认数据库有的，redis也要有）
    }


    /**
     * 解决缓存穿透、缓存击穿
     * @param id 查询的id
     * @return 店铺对象，为空说明没有
     */
    private Shop cacheThroughAndMutex(Long id){
        String resJson;
        //1、尝试从redis查询
        if((resJson=stringRedisTemplate.opsForValue().get(SHOP_CACHE_PREFIX+id))==null){
            try {
                //6、店铺未命中缓存，需要重建缓存，利用互斥锁解决缓存击穿
                if (tryLock(KEY_PREFIX + id)) {
                    //4、不存在，根据id查询数据库
                    Shop shop;
                    //5、不存在，返回错误(将空值写入redis，解决缓存穿透)
                    if((shop=getById(id))==null){
                        stringRedisTemplate.opsForValue().set(SHOP_CACHE_PREFIX+id,"",CACHE_NULL_OBJECT_EXPIRED,TimeUnit.MINUTES);
                        //返回没有该对象的错误，以及缓存了空对象进redis
                        return null;
                    }
                    stringRedisTemplate.opsForValue()
                            .set(SHOP_CACHE_PREFIX + id,
                                    JSONUtil.toJsonStr(shop),
                                    CACHE_SHOP_EXPIRED, TimeUnit.MINUTES);
                    return shop;
                } else {
                    Thread.sleep(THREAD_SLEEP_TIME);
                    cacheThroughAndMutex(id);
                }
            }catch (InterruptedException e){
                throw new RuntimeException(e);
            }finally {
                releaseLock(KEY_PREFIX+id);
            }
        } else if(!resJson.isEmpty()){
            //3、不为空则直接返回
            Shop shop;
            shop=JSONUtil.toBean(resJson,Shop.class);
            return shop;
        }
        return null;
    }

    /**
     * 解决缓存击穿
     */
    //线程池
    private static final Integer THREAD_NUM=10;
    ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(THREAD_NUM);
    private Shop logicExpired(Long id){
        String resJson;
        Shop shop;
        //1、尝试从redis查询
        if((resJson=stringRedisTemplate.opsForValue().get(SHOP_CACHE_PREFIX+id))==null) {
            //去数据库查询看看有没有这个id的店铺信息，如果有的话需要进行缓存重建
            if((shop=getById(id))!=null){
                //缓存重建
                if (tryLock(KEY_PREFIX+id)) {
                    //6、获取互斥锁
                    //7、获取锁成功，新线程重建
                    //创建线程池，写入缓存
                    CACHE_REBUILD_EXECUTOR.submit(()->{
                        try {
                            this.saveShop2Redis(id, 20L);
                        }catch (Exception e){
                            throw new RuntimeException(e);
                        }finally {
                            releaseLock(KEY_PREFIX+id);
                        }
                    });
                }
            }
            return shop;
        }else {
            //2、不为空json反序列化为对象
            RedisData res = JSONUtil.toBean(resJson, RedisData.class);
            //先将res的data转化为Json对象，然后to真正的Bean
            shop=JSONUtil.toBean((JSONObject) res.getData(),Shop.class);
            //3、判断是否过期
            if(res.getExpireTime().isAfter(LocalDateTime.now())){
                //4、未过期，返回
                return shop;
            }
            //5、过期（缓存重建）
            if (tryLock(KEY_PREFIX+id)) {
                //6、获取互斥锁
                //7、获取锁成功，新线程重建
                    //创建线程池，写入缓存
                    CACHE_REBUILD_EXECUTOR.submit(()->{
                        try {
                            this.saveShop2Redis(id, 20L);
                        }catch (Exception e){
                            throw new RuntimeException(e);
                        }finally {
                            releaseLock(KEY_PREFIX+id);
                        }
                    });
                }

            }
            //8、获取失败，返回店铺信息
            return shop;
    }
    private void saveShop2Redis(Long id,Long expiredSecond){
        //查询店铺id
        Shop shop =getById(id);
        //拓展结构，加上逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSecond));
        stringRedisTemplate.opsForValue().set(SHOP_CACHE_PREFIX+id, JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    public Result updateByShopId(Shop shop) {
        //检查参数合法性
        if(null==shop.getId())return Result.fail("店铺ID不能为空");
        //先操作数据库
        //再删除缓存
        updateById(shop);
        stringRedisTemplate.delete(SHOP_CACHE_PREFIX+shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopOfType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要分页查询
        if(null==x||null==y){
            // 根据类型分页查询
            Page<Shop> page = shopService.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            //解析id
            //根据id查询店铺并且返回
            return Result.ok(page.getRecords());
        }
        int from=(current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;
        final GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo().radius(
                SHOP_POSITION_PREFIX+typeId,
                new Circle(new Point(x,y),new Distance(5000)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().limit(end)
        );
        if(null==search)return Result.ok();
        List<Long> shopIds=new ArrayList<>();
        Map<String,Distance> distanceMap=new HashMap<>();
        search.getContent().stream().skip(from).forEach(
                a-> {
                    final String name = a.getContent().getName();
                    final Distance distance = a.getDistance();
                    shopIds.add(Long.valueOf(name));
                    distanceMap.put(name,distance);
                }
                    );

        String idStr = StrUtil.join( ",",shopIds);
        if(null==idStr||idStr.isEmpty())return Result.ok();
        List<Shop> list = query().in("id", shopIds).last("ORDER BY FIELD(id," + idStr + ")").list();
        list.forEach(a->a.setDistance(distanceMap.get(a.getId().toString()).getValue()));
        return Result.ok(list);
    }

    private boolean tryLock(String key){
        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key,KEY_VALUE, KEY_EXPIRED,
                TimeUnit.SECONDS));
    }
    private void releaseLock(String key){
        stringRedisTemplate.delete(key);
    }
}
