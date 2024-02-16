package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.common.ShopCommon.SHOP_POSITION_PREFIX;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    IShopService iShopService;

    @Resource
    IVoucherService ivoucherService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Test
    public void  test(){
        Voucher voucher=new Voucher();
        voucher.setShopId(1l);
        voucher.setTitle("100元代金券");
        voucher.setSubTitle("周一至周五均可使用");
        voucher.setRules("全场通用\\n无需预约\\n可无限叠加\\不兑现\\不找零\\n仅限堂食");
        voucher.setPayValue(8000l);
        voucher.setActualValue(10000l);
        voucher.setType(1);
        voucher.setStock(100);
        final val localDateTime = LocalDateTime.of(2024, 1, 21, 0, 0, 0);
        voucher.setBeginTime(localDateTime);
        final  val end=localDateTime.of(2024,1,25,0,0,0);
        voucher.setEndTime(end);
        ivoucherService.addSeckillVoucher(voucher);
    }
    @Test
    public void loadData(){
        final List<Shop> list = iShopService.list();
        Map<Long,List<Shop>> map=list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        //一个一个加，有点慢
        map.forEach((key, value) -> {
            List<RedisGeoCommands.GeoLocation<String>> locations = value.stream()
                    .map(s -> new RedisGeoCommands.GeoLocation<>(s.getId().toString(),
                            new Point(s.getX(), s.getY())))
                    .collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(SHOP_POSITION_PREFIX + key, locations);
        });


    }


}
