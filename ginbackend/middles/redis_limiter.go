// 基于Redis的分布式限流实现
package middles // 包声明：定义本文件所属包

import ( // 导入依赖包列表
	"context" // 上下文管理，用于控制操作生命周期
	"log"
	"net/http" // HTTP 状态码与响应
	"strconv"  // 字符串与数字转换
	"time"     // 时间与窗口周期

	"github.com/gin-gonic/gin"     // Gin Web 框架
	"github.com/go-redis/redis/v8" // Redis v8 客户端
)

// RedisLimiter Redis分布式限流器
type RedisLimiter struct { // 定义 Redis 限流器结构体
	redisClient *redis.Client // Redis 客户端实例
	keyPrefix   string        // 限流键前缀
	limit       int           // 每个窗口允许的最大请求数
	window      time.Duration // 时间窗口长度
} // 结构体结束

// NewRedisLimiter 创建一个基于Redis的限流器
func NewRedisLimiter(redisClient *redis.Client, keyPrefix string, limit int, window time.Duration) *RedisLimiter { // 构造函数：初始化限流器

	return &RedisLimiter{ // 返回限流器实例

		redisClient: redisClient, // 设置 Redis 客户端
		keyPrefix:   keyPrefix,   // 设置键前缀
		limit:       limit,       // 设置限流数量
		window:      window,      // 设置窗口时长
	} // 结束结构体字面量
} // 构造函数结束

// Allow 检查是否允许请求通过
func (l *RedisLimiter) Allow(key string) (bool, error) { // 判断给定 key 是否允许通过

	ctx := context.Background() // 创建上下文
	now := time.Now().Unix()    // 当前时间戳（秒）
	windowKey := l.keyPrefix + ":" + key + ":" + strconv.FormatInt(now/int64(l.window.Seconds()), 10)
	// 计算当前窗口的 Redis 键
	//  windowKey: ratelimit:127.0.0.1:29385363
	// 同一时间窗口的请求都累加到同一个键  这一时间窗口有多少个请求都累加到这一个key中
	// 时间窗口不一定是这一秒 而是把传过来的单位转化成多少秒的形式
	// now / int64(l.window.Seconds()) ：整数除法，得到当前时间所在的“窗口索引”
	log.Println("windowKey:", windowKey)

	// 使用Redis pipeline减少网络往返
	// 创建一个“命令队列”，把多个 Redis 命令一起打包发送，减少网络往返次数。
	pipe := l.redisClient.Pipeline() // 创建 Redis pipeline

	// 将窗口计数器自增
	incr := pipe.Incr(ctx, windowKey)
	// 设置键过期时间为窗口长度  这一秒结束后 这一个key就不存在了
	pipe.Expire(ctx, windowKey, l.window)
	_, err := pipe.Exec(ctx) // 执行上述管道命令
	if err != nil {          // 如果执行过程中出错

		return false, err // 返回不允许并携带错误
	} // 错误处理结束

	// 检查计数
	count, err := incr.Result() // 获取自增后的计数值
	if err != nil {             // 如果获取结果出错

		return false, err // 返回不允许并携带错误
	} // 错误处理结束

	return count <= int64(l.limit), nil // 在限额以内返回允许
} // Allow 方法结束

// RedisLimiterMiddleware 创建基于Redis的限流中间件
func RedisLimiterMiddleware(redisClient *redis.Client, keyPrefix string, limit int, window time.Duration) gin.HandlerFunc { // 返回 Gin 中间件

	limiter := NewRedisLimiter(redisClient, keyPrefix, limit, window) // 初始化限流器
	return func(c *gin.Context) {                                     // 定义并返回中间件处理函数

		// 使用IP作为限流键
		key := c.ClientIP() // 以客户端 IP 作为限流键

		allowed, err := limiter.Allow(key) // 判断当前请求是否允许
		if err != nil {                    // 如果限流器或 Redis 出现错误
			// ping一下 如果ping 不通说明断开连接了 请求不放行了
			if pingErr := redisClient.Ping(context.Background()).Err(); pingErr != nil {
				c.JSON(http.StatusServiceUnavailable, gin.H{
					"message": "Redis连接失败",
					"status":  "redis_unavailable",
				})
				c.Abort()
				return
			}

			// Redis错误时允许请求通过，避免因限流组件故障而阻止服务
			c.Next() // 放行请求
			return   // 结束当前处理
		} // 错误分支结束

		if !allowed { // 超过限制，拒绝请求

			c.JSON(http.StatusTooManyRequests, gin.H{ // 返回 429 状态与 JSON 提示

				"message": "请求频率超过限制",     // 提示信息
				"status":  "rate_limited", // 人类可读的状态
			}) // JSON 响应结束
			c.Abort() // 终止后续中间件与处理
			return    // 返回
		} // 超限分支结束

		c.Next() // 未超过限制，继续处理链
	} // 匿名中间件函数结束
} // RedisLimiterMiddleware 结束
