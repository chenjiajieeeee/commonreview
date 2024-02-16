package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.val;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private static final String FOLLOW_USER="follow::user::";
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result isFollow(Long followId) {
        val userId = UserHolder.getUser().getId();
        final Integer count = query().eq("user_id", userId).eq("follow_user_id", followId).count();
        return Result.ok(count!=null&&count>0);
    }

    @Override
    public Result follow(Long followId, Boolean isFollow) {
        final Long userId = UserHolder.getUser().getId();
        if(isFollow){
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followId);
            boolean res=save(follow);
            if(res){
                //把关注用户的id放入redis
                stringRedisTemplate.opsForSet().add(FOLLOW_USER+userId,followId.toString());
            }
        }else {
            //取关
            boolean res = remove(new QueryWrapper<Follow>()
                    .eq("user_id",userId).eq("follow_user_id",followId));

           if(res)stringRedisTemplate.opsForSet().remove(FOLLOW_USER+userId,followId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户
        final Long userId = UserHolder.getUser().getId();
        final Set<String> uti = stringRedisTemplate.opsForSet().
                intersect(FOLLOW_USER + id, FOLLOW_USER + userId);
        if(null==uti)return Result.ok(null);
        final List<UserDTO> collect = uti.stream().
                map(Integer::parseInt).
                map(usid -> userService.getById(usid)).
                map(user -> new UserDTO(user.getId(), user.getNickName(), user.getIcon()))
                .collect(Collectors.toList());
        return Result.ok(collect);
    }
}
