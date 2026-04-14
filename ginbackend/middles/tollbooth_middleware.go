// tollbooth_middleware.go
package middles

import (
	"github.com/didip/tollbooth"
	// Tollbooth库实现更强大的限流功能
	"github.com/gin-gonic/gin"
)

// TollboothLimiterMiddleware 使用Tollbooth库创建限流中间件
func TollboothLimiterMiddleware(max float64) gin.HandlerFunc {

	// 创建一个限流器，每秒最多max个请求
	lmt := tollbooth.NewLimiter(max, nil)

	// 自定义消息
	lmt.SetMessage("您的请求太频繁，请稍后再试")
	lmt.SetMessageContentType("application/json; charset=utf-8")

	// 设置根据多个参数进行限流
	lmt.SetIPLookups([]string{
		"RemoteAddr", "X-Forwarded-For", "X-Real-IP"})

	return func(c *gin.Context) {

		httpError := tollbooth.LimitByRequest(lmt, c.Writer, c.Request)
		if httpError != nil {

			c.Data(httpError.StatusCode, lmt.GetMessageContentType(), []byte(httpError.Message))
			c.Abort()
			return
		}
		c.Next()
	}
}
