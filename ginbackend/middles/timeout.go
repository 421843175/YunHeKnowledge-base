package middles

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
)

// Timeout 返回API超时中间件
func Timeout(timeout time.Duration) gin.HandlerFunc {
	return func(c *gin.Context) {
		// 创建一个带有超时的上下文
		ctx, cancel := context.WithTimeout(c.Request.Context(), timeout)
		defer cancel()

		// 将超时上下文替换原始上下文
		c.Request = c.Request.WithContext(ctx)

		// 创建一个完成通知通道
		done := make(chan bool, 1)
		// 创建一个元素类型为 bool 、容量为 1 的缓冲通道。

		// 使用goroutine处理请求
		go func() {
			c.Next()
			done <- true
		}()

		// 等待处理完成或超时
		select {
		case <-done:
			// 通道 done 接收一个值，但不保留它”，只要有值可读（或者通道已关闭），这个分支就会被选中
			// 请求正常处理完成
			return
		case <-ctx.Done():
			// 读到 通道已关闭  在到达超时时间后，内部会自动调用取消
			// 请求处理超时
			if ctx.Err() == context.DeadlineExceeded {
				c.AbortWithStatusJSON(http.StatusRequestTimeout, gin.H{
					"error": "请求处理超时",
				})
			}
			return
		}
	}
}
