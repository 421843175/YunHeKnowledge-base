package main

import (
	"log"
	"os"
	"time"

	"gobackend/middles"
	"gobackend/project/config"
	"gobackend/project/routes"

	"github.com/gin-gonic/gin"
)

func main() {
	gin.DefaultWriter = os.Stdout
	gin.DefaultErrorWriter = os.Stdout
	log.SetOutput(os.Stdout)

	config.InitDB()

	router := gin.New()

	// 基础中间件优先：日志、异常恢复
	router.Use(gin.Logger())
	router.Use(gin.Recovery())

	// JWT 只在具体需要鉴权的路由中使用，这里只做初始化
	middles.InitJWTMiddleware(middles.JWTConfig{
		SecretKey:  "default_secret_key_at_least_32_bytes_123",
		Expiration: 2 * time.Hour,
		Issuer:     "ai-enterprise-service",
	}, nil)

	// 全局更适合放通用型中间件
	// 0. 处理跨域预检和跨域响应头
	router.Use(middles.CORSMiddleware())
	// 1. 先限流，尽早拦截高频请求
	router.Use(middles.TollboothLimiterMiddleware(100))
	// 2. 再做超时控制，避免请求长期占用资源
	router.Use(middles.Timeout(15 * time.Second))

	// 注意：Hystrix 和 CircuitBreaker 都属于熔断类中间件，
	// 同时全局启用容易造成重复熔断、重复响应和调试困难。
	// 这里先不全局挂载，后续如果某些外部依赖接口需要，再按具体路由单独使用。
	// router.Use(middles.HystrixMiddleware(middles.NewDefaultHystrixConfig("global-hystrix")))
	// router.Use(middles.CircuitBreakerMiddleware(middles.NewCircuitBreakerConfig("global-breaker")))

	routes.SetUpRoutes(router)

	router.Run(":8090")
}
