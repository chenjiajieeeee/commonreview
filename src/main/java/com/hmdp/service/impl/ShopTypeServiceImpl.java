package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.common.ShopCommon.TYPE_LIST_PREFIX;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        List<ShopType> shopTypes;
        //尝试去redis查询
        String resJson;
        if(StrUtil.isBlank(resJson=stringRedisTemplate.opsForValue().get(TYPE_LIST_PREFIX))){
            //未命中去数据库查询
            shopTypes = query().orderByAsc("sort").list();
            //查询完放入
            stringRedisTemplate.opsForValue().set(TYPE_LIST_PREFIX, JSONUtil.toJsonStr(shopTypes));
        }else{
            //赋值，返回
            shopTypes=JSONUtil.toList(resJson,ShopType.class);
        }
        return Result.ok(shopTypes);
    }
}
