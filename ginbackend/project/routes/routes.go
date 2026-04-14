package routes

import "github.com/gin-gonic/gin"

func SetUpRoutes(router *gin.Engine) {
	//设置API路由
	SetupAPIRoutes(router)

	// 设置网站路由
	// SetupWebRoutes(router)

	// 设置管理后台路由
	// SetupAdminRoutes(router)  //不实现了
}
