// middleware/rate_limiter.go

package middles

import (
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/time/rate"
)

// IPRateLimiter IP限流器
type IPRateLimiter struct {
	ips      map[string]*rate.Limiter // 存储每个IP的限流器
	mu       *sync.RWMutex
	rate     rate.Limit
	burst    int
	expiry   time.Duration        // 限流器过期时间
	lastSeen map[string]time.Time // 记录每个IP最后一次访问时间
}

// NewIPRateLimiter 创建新的IP限流器
func NewIPRateLimiter(r rate.Limit, b int, expiry time.Duration) *IPRateLimiter {
	return &IPRateLimiter{
		ips:      make(map[string]*rate.Limiter),
		mu:       &sync.RWMutex{},
		rate:     r,
		burst:    b,
		expiry:   expiry,
		lastSeen: make(map[string]time.Time),
	}
}

// GetLimiter 获取给定IP的限流器
func (i *IPRateLimiter) GetLimiter(ip string) *rate.Limiter {
	// 这里上读所因为
	// 	Go语言中的map 不是并发安全的 ：

	// - 如果多个goroutine同时读写同一个map，会导致 数据竞争
	// - 可能出现程序崩溃或数据不一致的问题
	//  使用读锁写操作会被阻塞
	i.mu.RLock()
	// GO语言map的查询语法
	// 这种语法的好处是可以区分"键不存在"和"键存在但值为零值"这两种情况
	limiter, exists := i.ips[ip]
	now := time.Now()

	// 检查上次访问时间，清理过期的限流器
	if exists {
		lastSeen, ok := i.lastSeen[ip]
		//now.Sub(lastSeen) > i.expiry 检查上次访问时间是否超过过期时间
		if ok && now.Sub(lastSeen) > i.expiry {
			exists = false
		}
	}

	i.mu.RUnlock()

	if !exists {
		i.mu.Lock()
		// 创建新的限流器
		limiter = rate.NewLimiter(i.rate, i.burst)
		i.ips[ip] = limiter
		i.lastSeen[ip] = now

		// 清理过期的限流器
		if len(i.ips) > 10000 { // 避免无限增长
			for ip, t := range i.lastSeen {
				if now.Sub(t) > i.expiry {
					delete(i.ips, ip)
					delete(i.lastSeen, ip)
				}
			}
		}

		i.mu.Unlock()
	} else {
		// 更新最后访问时间
		i.mu.Lock()
		i.lastSeen[ip] = now
		i.mu.Unlock()
	}

	return limiter
}

// TODO:NOTICE IP限流中间件
// RateLimiter 返回IP限流中间件
// r: 每秒请求速率
// b: 突发请求数
// 每个IP每秒最多r个请求 突发b个请求
// 意思是每秒补充r个令牌，桶内最多b个令牌
func RateLimiter(r float64, b int) gin.HandlerFunc {
	// 创建IP限流器实例，限流器过期时间1小时
	limiter := NewIPRateLimiter(rate.Limit(r), b, time.Hour)

	return func(c *gin.Context) {
		// 获取客户端IP
		ip := c.ClientIP()

		// 获取该IP的限流器
		ipLimiter := limiter.GetLimiter(ip)

		// 检查是否允许请求
		// Allow函数内部会 计算时间间隔 根据时间补充令牌
		// 然后 检查是否有足够的令牌
		// 如果没有足够的令牌，会返回false
		if !ipLimiter.Allow() {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"error": "请求频率超限，请稍后再试",
			})
			return
		}

		c.Next()
	}
}
