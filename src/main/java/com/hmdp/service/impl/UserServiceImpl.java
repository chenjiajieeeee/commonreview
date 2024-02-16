package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.common.UserCommon.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    StringRedisTemplate stringRedisTemplate;




    @Override
    public Result sendCode(String phone, HttpSession session) {
        LoginFormDTO loginFormDTO=new LoginFormDTO();
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            return Result.fail("手机号格式错误");
//        }
        val string = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(CODE_PREFIX+phone,string,OUT_DATE_MIN, TimeUnit.MINUTES);
        log.debug("发送验证码成功:"+string);
        loginFormDTO.setCode(string);
        loginFormDTO.setPhone(phone);
        return Result.ok(loginFormDTO);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone;
        String cacheCode;
        User user;
        //||RegexUtils.isPhoneInvalid(phone)
        //1、校验手机号
        if((phone=loginForm.getPhone())==null)return Result.fail("电话号码错误");
        //2、校验验证码
        if((cacheCode=stringRedisTemplate.opsForValue().get(CODE_PREFIX+phone))==null||!cacheCode.equals(loginForm.getCode()))
            return Result.fail("验证码错误");

        //3、一致到数据库查询用户
        if((user=query().eq("phone", phone).one())==null){
            user=createUserWithPhone(phone);
        }
        //生成UUID唯一令牌
        String token= TOKEN_PREFIX+ UUID.randomUUID();
       //保存到redis

        stringRedisTemplate.opsForHash().putAll(token,BeanUtil.beanToMap(BeanUtil.copyProperties(user,UserDTO.class)));
        stringRedisTemplate.expire(TOKEN_PREFIX+token,LOG_TIME_OUT,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1、获取当前用户
        final Long id = UserHolder.getUser().getId();
        final LocalDateTime now = LocalDateTime.now();
        final String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        final int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(SIGN_PREFIX+id+yyyyMM,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result userSignCount() {
        //获取用户
        final Long id = UserHolder.getUser().getId();
        final LocalDateTime now = LocalDateTime.now();
        final String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        final int dayOfMonth = now.getDayOfMonth();
        final List<Long> longs = stringRedisTemplate.opsForValue().bitField(SIGN_PREFIX + id + yyyyMM,
                BitFieldSubCommands.
                        create().
                        get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        if(null==longs||longs.isEmpty()){
            Result.ok(0);
        }
        assert longs != null;
        Long l = longs.get(0);
        if(null==l||l==0)return Result.ok(0);
        int res=0;
        while(l>0){
            if((l&1)==0)break;
            else res++;
            l=l>>1;
        }
        return Result.ok(res);
    }


    private User createUserWithPhone(String phone) {
        //1、创建用户

        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_PREFIX+RandomUtil.randomString(10));
        //2、保存用户
        save(user);
        return user;
    }
}
