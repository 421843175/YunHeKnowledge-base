// gobreaker 熔断器
// circuit_breaker.go
package middles

import (
	"errors"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/sony/gobreaker"
)

// CircuitBreakerConfig 熔断器配置
type CircuitBreakerConfig struct {

	// 熔断器名称
	Name string
	// 触发熔断的连续失败次数
	MaxRequests uint32
	// 熔断后的冷却时间
	Interval time.Duration
	// 半开状态允许的请求数
	Timeout time.Duration
	// 判断请求是否成功的函数
	ReadyToTrip func(counts gobreaker.Counts) bool
	// 熔断器打开时的回调函数
	OnStateChange func(name string, from gobreaker.State, to gobreaker.State)
}

// NewCircuitBreakerConfig 创建默认熔断器配置
func NewCircuitBreakerConfig(name string) CircuitBreakerConfig {

	return CircuitBreakerConfig{

		Name:        name,
		MaxRequests: 5,                // 半开状态下允许的请求数
		Interval:    60 * time.Second, // 熔断时间窗口
		Timeout:     30 * time.Second, // 熔断后冷却时间
		ReadyToTrip: func(counts gobreaker.Counts) bool {

			// 连续5次失败或失败率超过60%时触发熔断
			if counts.Requests == 0 {
				return counts.ConsecutiveFailures > 5
			}
			failureRatio := float64(counts.TotalFailures) / float64(counts.Requests)
			return counts.ConsecutiveFailures > 5 || (counts.Requests > 10 && failureRatio >= 0.6)
		},
		OnStateChange: func(name string, from gobreaker.State, to gobreaker.State) {

			// 状态变化时记录日志
			switch to {

			case gobreaker.StateOpen:
				println("Circuit breaker", name, "is open (tripped)")
			case gobreaker.StateHalfOpen:
				println("Circuit breaker", name, "is half-open (recovering)")
			case gobreaker.StateClosed:
				println("Circuit breaker", name, "is closed (operational)")
			}
		},
	}
}

// CircuitBreakerMiddleware 创建熔断器中间件
func CircuitBreakerMiddleware(config CircuitBreakerConfig) gin.HandlerFunc {

	// 创建熔断器
	cb := gobreaker.NewCircuitBreaker(gobreaker.Settings{

		Name:          config.Name,
		MaxRequests:   config.MaxRequests,
		Interval:      config.Interval,
		Timeout:       config.Timeout,
		ReadyToTrip:   config.ReadyToTrip,
		OnStateChange: config.OnStateChange,
	})

	return func(c *gin.Context) {

		// 通过熔断器执行请求
		result, err := cb.Execute(func() (interface {
		}, error) {

			// 创建一个特殊的writer记录响应
			writer := &responseWriter{
				ResponseWriter: c.Writer}
			// 将writer给了c.writer()，后续的响应都将写入到writer中
			c.Writer = writer

			// 执行请求处理链
			c.Next()

			// 检查状态码，非2xx状态码被视为错误
			if writer.Status() >= 400 {

				return nil, errors.New("request failed with status: " + http.StatusText(writer.Status()))
			}

			return nil, nil
		})

		// 如果熔断器打开，或者请求执行失败
		if err != nil {

			// 检查是否是熔断器打开状态
			if errors.Is(err, gobreaker.ErrOpenState) {
				// TODO:NOTICE  走熔断器逻辑
				// 返回服务不可用
				c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{

					"error":   "Service temporarily unavailable(服务不可用 触发熔断)",
					"message": "Circuit breaker is open",
				})
				return
			}

			// 其他执行错误，正常处理，因为请求处理链已经执行
		}

		// 熔断器成功执行，继续处理
		_ = result
	}
}

// responseWriter 用于捕获响应状态
type responseWriter struct {
	gin.ResponseWriter
	statusCode int
}

func (w *responseWriter) WriteHeader(code int) {

	w.statusCode = code
	w.ResponseWriter.WriteHeader(code)
}

func (w *responseWriter) Status() int {

	if w.statusCode == 0 {
		return w.ResponseWriter.Status()
	}
	return w.statusCode
}

// *********************多路由熔断器*****************
// CircuitBreakerFactory 熔断器工厂，管理多个熔断器
type CircuitBreakerFactory struct {
	breakers map[string]*gobreaker.CircuitBreaker
	configs  map[string]CircuitBreakerConfig
	mu       sync.RWMutex
}

// NewCircuitBreakerFactory 创建熔断器工厂
func NewCircuitBreakerFactory() *CircuitBreakerFactory {

	return &CircuitBreakerFactory{

		breakers: make(map[string]*gobreaker.CircuitBreaker),
		configs:  make(map[string]CircuitBreakerConfig),
	}
}

// RegisterCircuitBreaker 注册一个熔断器配置
func (f *CircuitBreakerFactory) RegisterCircuitBreaker(path string, config CircuitBreakerConfig) {

	f.mu.Lock()
	defer f.mu.Unlock()

	f.configs[path] = config
	f.breakers[path] = gobreaker.NewCircuitBreaker(gobreaker.Settings{

		Name:          config.Name,
		MaxRequests:   config.MaxRequests,
		Interval:      config.Interval,
		Timeout:       config.Timeout,
		ReadyToTrip:   config.ReadyToTrip,
		OnStateChange: config.OnStateChange,
	})
}

// GetCircuitBreaker 获取指定路径的熔断器
func (f *CircuitBreakerFactory) GetCircuitBreaker(path string) (*gobreaker.CircuitBreaker, bool) {

	f.mu.RLock()
	defer f.mu.RUnlock()

	cb, exists := f.breakers[path]
	return cb, exists
}

// CircuitBreakerByPathMiddleware 按路径应用不同熔断器的中间件
func CircuitBreakerByPathMiddleware(factory *CircuitBreakerFactory) gin.HandlerFunc {

	return func(c *gin.Context) {

		path := c.FullPath()
		if path == "" {

			path = c.Request.URL.Path
		}

		// 尝试获取该路径的熔断器
		cb, exists := factory.GetCircuitBreaker(path)
		if !exists {

			// 没有为此路径配置熔断器，直接处理请求
			c.Next()
			return
		}

		// 使用找到的熔断器执行请求
		result, err := cb.Execute(func() (interface {
		}, error) {

			writer := &responseWriter{
				ResponseWriter: c.Writer}
			c.Writer = writer
			c.Next()

			if writer.Status() >= 400 {

				// TODO:NOTICE 多路由的触发熔断
				return nil, errors.New("request failed(服务不可用 触发熔断)")
			}
			return nil, nil
		})

		if err != nil {

			if errors.Is(err, gobreaker.ErrOpenState) {

				c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{

					"error":   "Service temporarily unavailable",
					"message": "Circuit breaker is open",
				})
				return
			}
		}

		_ = result
	}
}
