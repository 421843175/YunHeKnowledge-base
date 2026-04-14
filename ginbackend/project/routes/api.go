package routes

import (
	"gobackend/middles"
	"gobackend/project/controller"

	"github.com/gin-gonic/gin"
)

func SetupAPIRoutes(router *gin.Engine) {
	api := router.Group("/api")

	v1 := api.Group("/v1")
	setupUserRoutesV1(v1)
	setupEnterpriseRoutesV1(v1)
}

func setupUserRoutesV1(rg *gin.RouterGroup) {
	users := rg.Group("/users")
	{
		users.GET("", controller.GetAllUsersV1)
		users.GET("/:id", controller.GetUserV1)
		users.GET("/page", controller.GetPageUserV1)
		users.POST("", controller.CreateUserV1)
		users.POST("/register", controller.RegisterUserV1)
		users.POST("/login", controller.LoginUserV1)
		users.GET("/audit/pending", middles.JWTMiddleware(), controller.GetPendingAuditUsersV1)
		users.POST("/audit", middles.JWTMiddleware(), controller.AuditUserV1)
		users.PUT("/:id", controller.UpdateUserV1)
		users.PATCH("/:id", controller.UpdateUserFieldsV1)
		users.DELETE("/:id", controller.DeleteUserV1)
		users.DELETE("/:id/hard", controller.HardDeleteUserV1)
	}
}

func setupEnterpriseRoutesV1(rg *gin.RouterGroup) {
	enterprises := rg.Group("/enterprises")
	{
		enterprises.POST("/apply", controller.ApplyEnterpriseV1)
		enterprises.GET("/list", controller.ListEnterpriseV1)
	}
}
