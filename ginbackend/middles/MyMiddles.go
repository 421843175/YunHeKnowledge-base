// 这里不是go.mod里面的gobackend 而是文件夹
package middles

/*
中间件责任链模式：
  特性 责任链模式 顺序执行
  控制权 每个环节可决定是否继续 必须按顺序全部执行
  中断机制 支持 c.Abort() 中断 只能通过 return 退出
  双向处理 支持请求前/响应后处理 单向处理
  灵活性 高，可动态调整执行流程 低，固定执行顺序
  复用性 高，中间件可独立复用 低，逻辑耦合在一起
  调试难度 较难，需要理解执行栈 简单，线性执行
中间件的通讯：
  		Q:如何在多个中间件之间共享和传递数据，特别是在微服务架构中？

		A:在Gin框架中，多个中间件之间共享数据的主要方式是通过gin.Context对象。
		在单个请求范围内，可以使用c.Set(key, value)存储数据，
		然后在后续中间件或处理函数中通过c.Get(key)获取
*/
import (
	"gobackend/utils"
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// 日志中间件
func LoggerMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()
		//设置请求id
		requestID := time.Now().UnixNano() //纳秒级时间戳
		c.Set("RequestID", requestID)

		//处理请求
		c.Next()

		//记录请求信息
		latency := time.Since(startTime) //请求耗时
		//记录日志
		log.Printf("my:[%d]%s %s %v %d",
			requestID,
			c.Request.Method,
			c.Request.URL.Path,
			latency,
			c.Writer.Status())
	}

}

// 请求ID生成中间件
func RequestIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := time.Now().UnixNano() //纳秒级时间戳
		c.Set("RequestID", requestID)
		c.Next()
	}
}

func ErrorHandlerMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		//设置恢复函数
		defer func() {
			//if err := recover(); err != nil 这种写法等效于
			/*
				err := recover()
				if err != nil {
					// 处理错误
				}
			*/
			if err := recover(); err != nil { //recover捕获异常
				requestID, _ := c.Get("RequestID")
				log.Printf("[%v] 恢复自异常: %v", requestID, err)
				//返回错误响应
				utils.HttpErrorResponse(c, 500, "Internal Server Error")
			}
		}()

		c.Next()

		//处理错误链的错误
		if len(c.Errors) > 0 {
			//在请求中通过 errors.New("自定义错误信息") 来添加自定义错误
			err := c.Errors.Last().Err //获取错误链的最后一个错误
			//c.Errors.Last() 取的是“最新追加的那个错误”，通常也就是本次请求最终失败的直接原因。

			// 不使用requestID
			// _requestID, _ := c.Get("RequestID")
			log.Println("这里触发了错误中间件:", err.Error())
			//返回500错误
			if appErr, ok := err.(*utils.Response); ok {
				utils.ErrorResponse(c, appErr.Error())
			} else {
				// 处理其他错误
				utils.HttpErrorResponse(c, 500, err.Error())
			}

		}
	}

}

// 请求添加ID方便追踪中间件
func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		// 从请求头获取请求ID
		requestID := c.GetHeader("X-Request-ID")

		// 如果没有，则生成一个
		if requestID == "" {
			requestID = uuid.New().String()
		}

		// 设置请求ID到上下文
		c.Set("RequestID", requestID)

		// 添加到响应头
		c.Header("X-Request-ID", requestID)

		c.Next()
	}
}

// CORS中间件
/*
TODO:NOTICE  关于AbortWithStatus 和 Abort
  AbortWithStatus： 只需要返回状态码，无需响应体时
  Abort： 需要自定义响应内容 然后Abort
*/
func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		origin := c.GetHeader("Origin")
		if origin == "" {
			origin = "*"
		}

		c.Writer.Header().Set("Access-Control-Allow-Origin", origin)
		c.Writer.Header().Set("Vary", "Origin")
		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization, accept, origin, Cache-Control, X-Requested-With")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS, GET, PUT, DELETE, PATCH")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}

		c.Next()
	}
}

func CSRFProtectionMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		// 只处理修改数据的请求方法
		if c.Request.Method == "POST" || c.Request.Method == "PUT" ||
			c.Request.Method == "DELETE" || c.Request.Method == "PATCH" {

			token := c.GetHeader("X-CSRF-Token")
			if token == "" || !validateCSRFToken(token, c) {
				c.JSON(http.StatusForbidden, gin.H{
					"error": "CSRF验证失败",
				})
				c.Abort()
				return
			}
		}

		c.Next()
	}
}

func validateCSRFToken(token string, c *gin.Context) bool {
	// 从会话中获取CSRF令牌
	sessionToken, err := c.Cookie("CSRF-Token")
	if err != nil {
		return false
	}

	// 比较令牌是否匹配
	return token == sessionToken
}
