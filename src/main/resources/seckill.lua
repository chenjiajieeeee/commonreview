--判断库存是否充足
--1.1优惠券id
local voucherId=ARGV[1]
--1.2用户id
local userId=ARGV[2]
--1.3保存的订单id
local orderId=ARGV[3]

voucherId = string.gsub(voucherId, '"', '')
userId = string.gsub(userId, '"', '')
orderId = string.gsub(orderId, '"', '')

--key库存
local stockkey= 'seckill::stock' .. voucherId
--订单key
local orderkey= 'seckill::order' .. voucherId


local stockValue = redis.call('get', stockkey)
stockValue=string.gsub(stockValue,'"','')
-- 检查值是否为 false 或者为 nil
if stockValue == false or stockValue == nil then
    return 3
end
--脚本业务
if(tonumber(stockValue)<=0) then
    --库存不足返回1
    return 1
end
--判断是否重复下单
if(redis.call('sismember',orderkey,userId)==1) then
    return 2
end

--3.4加库存，,
redis.call('set',stockkey,stockValue-1)
--下单
redis.call('sadd',orderkey,userId)
--发送消息到队列中 ，XADD STREAM.ORDER + * K1 V1 K2 V2
redis.call('xadd','stream.order','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
