// 限流和熔断结合起来 // 文件用途说明
// protection.go // 文件名
package middles // 包名：middles

import ( // 依赖导入
	"errors"   // 构造与传递错误
	"net/http" // HTTP 状态码与响应
	"time"     // 时间窗口与延迟

	"github.com/afex/hystrix-go/hystrix" // Hystrix 熔断库
	"github.com/gin-gonic/gin"           // Gin Web 框架
	"github.com/go-redis/redis/v8"       // Redis 客户端 v8
) // 导入结束

// ProtectionLayer 组合限流和熔断功能的保护层
type ProtectionLayer struct {
	redisClient   *redis.Client // Redis 客户端，用于限流计数
	limiterKey    string        // 限流键前缀或命名空间
	limiterRate   int           // 限流速率（窗口内允许次数）
	limiterWindow time.Duration // 限流时间窗口
	circuitName   string        // 熔断器名称（命令名）
	hystrixConfig HystrixConfig // Hystrix 配置参数
}

// NewProtectionLayer 创建新的保护层
func NewProtectionLayer(
	redisClient *redis.Client, // Redis 客户端
	limiterKey string, // 限流键
	limiterRate int, // 限流速率
	limiterWindow time.Duration, // 限流窗口
	circuitName string, // 熔断器名称
	hystrixConfig HystrixConfig, // Hystrix 配置
) *ProtectionLayer {

	return &ProtectionLayer{ // 返回保护层实例

		redisClient:   redisClient,   // 绑定 Redis 客户端
		limiterKey:    limiterKey,    // 设置限流键
		limiterRate:   limiterRate,   // 设置速率
		limiterWindow: limiterWindow, // 设置窗口
		circuitName:   circuitName,   // 设置熔断名称
		hystrixConfig: hystrixConfig, // 设置熔断配置
	}
}

// Middleware 返回组合了限流和熔断功能的中间件
func (p *ProtectionLayer) Middleware() gin.HandlerFunc { // 中间件工厂

	// 配置Hystrix
	hystrix.ConfigureCommand(p.circuitName, hystrix.CommandConfig{ // 配置命令参数

		Timeout:                p.hystrixConfig.Timeout,                // 超时时间
		MaxConcurrentRequests:  p.hystrixConfig.MaxConcurrentRequests,  // 最大并发数
		RequestVolumeThreshold: p.hystrixConfig.RequestVolumeThreshold, // 统计窗口内最小请求量
		SleepWindow:            p.hystrixConfig.SleepWindow,            // 熔断后休眠时间
		ErrorPercentThreshold:  p.hystrixConfig.ErrorPercentThreshold,  // 错误百分比阈值
	}) // 配置结束

	// 创建Redis限流器
	limiter := NewRedisLimiter(p.redisClient, p.limiterKey, p.limiterRate, p.limiterWindow) // 初始化限流器

	return func(c *gin.Context) { // 返回中间件闭包

		// 步骤1: 先进行限流检查
		key := c.ClientIP()                // 使用客户端 IP 作为限流键
		allowed, err := limiter.Allow(key) // 判断是否允许通过

		if err != nil { // 限流器发生错误

			// Redis错误，继续处理，但记录日志
			// 在生产环境中，可能需要更健壮的错误处理
			println("Redis limiting error:", err.Error()) // 打印错误信息
		} else if !allowed { // 不允许通过（触发限流）

			// 请求被限流
			c.JSON(http.StatusTooManyRequests, gin.H{ // 返回 429 响应

				"error":   "Too many requests", // 错误描述
				"message": "请稍后再试",             // 用户提示
			})
			c.Abort() // 中止后续处理链
			return    // 结束当前请求
		}

		// 步骤2: 通过限流检查后，使用熔断器处理请求
		err = hystrix.Do(p.circuitName, func() error { // 执行受熔断保护的逻辑

			c.Next() // 执行后续处理（具体业务处理）

			// 判断请求是否成功
			if c.Writer.Status() >= 500 { // 5xx 视为失败

				return errors.New("服务器错误") // 返回错误用于统计
			}
			return nil // 成功
		}, func(err error) error { // 熔断降级回调

			// 熔断降级处理（若尚未写出响应）
			if !c.Writer.Written() {
				c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{ // 返回 503 降级响应
					"error":   "Service unavailable",
					"message": "服务暂时不可用，请稍后再试",
				})
			}
			return nil // 已降级，不再传递错误
		}) // hystrix.Do 结束

		if err != nil { // 如果熔断降级处理也返回了错误
			// 如果之前未写出响应，则返回普通错误
			if !c.Writer.Written() {
				c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{ // 返回 500
					"error":   "Internal server error",
					"message": "系统内部错误",
				})
			}
		}
	} // 中间件闭包结束
} // Middleware 结束
