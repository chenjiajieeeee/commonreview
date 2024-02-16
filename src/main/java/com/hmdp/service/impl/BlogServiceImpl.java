package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.val;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IBlogService blogService;

    @Resource
    private IFollowService followService;

    private static final String LIKE_SET_BLOG ="like::blog::";
    private static final String FEED_USER_PREFIX="feed::user::";
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryBlogById(Long id) {
        //1、查询博客
        Blog  blog;
        if((blog= getById(id))==null){
            return Result.fail("博客不存在");
        }
        //2、查询用户
        queryBlogUser(blog);
        isLikeBlog(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryBlogHot(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isLikeBlog(blog);
        });
        return Result.ok(records);

    }

    @Override
    public Result likeBlog(Long id) {
        //判断当前用户是否点赞
        //获取用户
        val id1 = UserHolder.getUser().getId();
        val res = stringRedisTemplate.opsForZSet().score(LIKE_SET_BLOG+id, id1.toString());
        if(null==res){
            //不点赞给他点（数据库点赞数加1）//redis保存用户点赞
            val check = update().setSql("liked=liked+1").eq("id", id).update();
            if(check){
                stringRedisTemplate.opsForZSet().add(LIKE_SET_BLOG+id,id1.toString(),System.currentTimeMillis());
            }else{
                return Result.fail("数据库写入异常");
            }
        }else {
            //把用户从set集合移除
            val check = update().setSql("liked=liked-1").eq("id", id).update();
            if(check){
                stringRedisTemplate.opsForZSet().remove(LIKE_SET_BLOG+id,id1.toString());
            }else{
                return Result.fail("数据库写入异常");
            }
        }

        return Result.ok("点赞成功");
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1、
        val range = stringRedisTemplate.opsForZSet().range(LIKE_SET_BLOG + id, 0, 4);
        //解析出userid
        if(null==range||range.isEmpty())return Result.ok(null);
        List<UserDTO> res=range.stream().
                map(Integer::parseInt).
                map(userid->userService.getById(userid)).
                map(user->new UserDTO(user.getId(),user.getNickName(),user.getIcon())).
                collect(Collectors.toList());
        return  Result.ok(res);
        //返回
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        final boolean save = blogService.save(blog);
        if(save) {
            // 返回id
            // 找到用户的粉丝
            List<Follow> followUserId = followService.query().eq("follow_user_id", user.getId()).list();

            if(null==followUserId||followUserId.isEmpty())return Result.ok(blog.getId());
            followUserId
                    .forEach(follow->stringRedisTemplate.opsForZSet().
                            add(FEED_USER_PREFIX+follow.getUserId(),
                                    blog.getId().toString(),
                                    System.currentTimeMillis()));

        }else {
            return Result.fail("服务器异常");
        }
        //推送
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        final Long userId = UserHolder.getUser().getId();
        //查询收件箱
        final Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.
                opsForZSet().
                reverseRangeByScoreWithScores(FEED_USER_PREFIX+userId, 0, max, offset, 2);
        if(null==typedTuples||typedTuples.isEmpty())return Result.ok();
        //解析数据：blogId，mintime，offset
        long minTime=0;
        for(val tuple:typedTuples){
            val cur=Objects.requireNonNull(tuple.getScore()).longValue();
            if(minTime== cur){
                offset++;
            }else {
                offset = 1;
                minTime = cur;
            }
        }
        final List<Long> blogIds = typedTuples.stream().
                map(ZSetOperations.TypedTuple::getValue).
                filter(Objects::nonNull).
                map(Long::valueOf).
                collect(Collectors.toList());
        //根据id查询blog，封装并且返回,这里进行了多次io数据库，不好
        final List<Blog> collect = blogIds.stream().
                map(this::getById).collect(Collectors.toList());
        collect.forEach(blog -> {this.queryBlogUser(blog);this.isLikeBlog(blog);});
        ScrollResult scrollResult=new ScrollResult();
        scrollResult.setList(collect);
        scrollResult.setOffset(offset);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

    }
    private void isLikeBlog(Blog blog){
        if(null==UserHolder.getUser())return;
        val id1 = UserHolder.getUser().getId();
        val res = stringRedisTemplate.opsForZSet().score(LIKE_SET_BLOG+blog.getId(), id1.toString());
        blog.setIsLike(null != res);
    }
}
