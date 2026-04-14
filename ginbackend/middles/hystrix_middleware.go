// hystrix_middleware.go
package middles

import (
	"errors"
	"net/http"
	"time"

	"github.com/afex/hystrix-go/hystrix"
	"github.com/gin-gonic/gin"
)

// HystrixConfig Hystrix配置参数
type HystrixConfig struct {
	Name                   string
	Timeout                int
	MaxConcurrentRequests  int
	RequestVolumeThreshold int
	SleepWindow            int
	ErrorPercentThreshold  int
}

// NewDefaultHystrixConfig 创建默认Hystrix配置
func NewDefaultHystrixConfig(name string) HystrixConfig {

	return HystrixConfig{

		Name:                   name,
		Timeout:                1000, // 命令超时时间（毫秒）
		MaxConcurrentRequests:  100,  // 最大并发请求数
		RequestVolumeThreshold: 20,   // 触发熔断的最小请求量
		SleepWindow:            5000, // 熔断器打开后多久尝试半开（毫秒）
		ErrorPercentThreshold:  50,   // 触发熔断的错误百分比
	}
}

// HystrixMiddleware 创建基于Hystrix的熔断中间件
func HystrixMiddleware(config HystrixConfig) gin.HandlerFunc {

	// 配置Hystrix命令
	hystrix.ConfigureCommand(config.Name, hystrix.CommandConfig{

		Timeout:                config.Timeout,
		MaxConcurrentRequests:  config.MaxConcurrentRequests,
		RequestVolumeThreshold: config.RequestVolumeThreshold,
		SleepWindow:            config.SleepWindow,
		ErrorPercentThreshold:  config.ErrorPercentThreshold,
	})

	return func(c *gin.Context) {

		// 使用Hystrix执行请求
		err := hystrix.Do(config.Name, func() error {

			// 创建通道来表示请求完成
			done := make(chan bool, 1)
			var handlerErr error

			// 在goroutine中执行请求处理
			go func() {

				// 捕获响应状态
				writer := &responseWriter{
					ResponseWriter: c.Writer}
				c.Writer = writer

				// 执行后续中间件和处理函数
				c.Next()

				// 检查请求是否成功
				if writer.Status() >= 400 {

					handlerErr = errors.New("request failed with status: " + http.StatusText(writer.Status()))
				}

				// 通知请求已完成
				done <- true
			}()

			// 等待请求完成或超时
			select {

			case <-done:
				return handlerErr
			case <-time.After(time.Duration(config.Timeout) * time.Millisecond):
				return errors.New("request timeout")
			}
		}, func(err error) error {

			// 这是降级函数，当熔断器打开或请求失败时执行
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{

				"error":   "Service unavailable",
				"message": "Service is temporarily unavailable. Please try again later.",
			})
			return nil
		})

		// 如果Do方法返回错误，说明降级函数也失败了
		if err != nil {

			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{

				"error":   "Internal server error",
				"message": "An unexpected error occurred.",
			})
		}
	}
}
