package middles

import (
	"gobackend/middles/models"
	"log"
	"net/http"

	"github.com/gin-gonic/gin"
)

// GetUserRolesFromContext 从上下文中获取用户角色
func GetUserRolesFromContext(c *gin.Context) []string {
	// 从上下文中获取用户ID（由JWTAuth中间件放入）
	jwtClaims, exists := c.Get("jwt_claims")
	if !exists {
		return []string{"guest"}
	}
	userID := jwtClaims.(*CustomClaims).UserID
	log.Println("myuserID:", userID)

	// 查找用户
	for _, user := range models.Users {
		if user.ID == uint(userID) {
			return user.Roles
		}
	}

	return []string{"guest"}
}

// RequirePermission 要求用户拥有特定权限
func RequirePermission(permission models.Permission) gin.HandlerFunc {
	return func(c *gin.Context) {
		// 获取用户角色
		roles := GetUserRolesFromContext(c)

		// 检查权限
		if !models.HasPermission(roles, permission) {
			c.JSON(http.StatusForbidden, gin.H{
				"error": "权限不足",
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

// RequireAllPermissions 要求用户拥有所有指定权限
func RequireAllPermissions(permissions ...models.Permission) gin.HandlerFunc {
	return func(c *gin.Context) {
		// 获取用户角色
		roles := GetUserRolesFromContext(c)

		// 检查所有权限
		if !models.HasAllPermissions(roles, permissions) {
			c.JSON(http.StatusForbidden, gin.H{
				"error": "权限不足，需要所有指定权限",
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

// RequireAnyPermission 要求用户拥有任意一个指定权限
func RequireAnyPermission(permissions ...models.Permission) gin.HandlerFunc {
	return func(c *gin.Context) {
		// 获取用户角色
		roles := GetUserRolesFromContext(c)

		// 检查是否有任意一个权限
		if !models.HasAnyPermission(roles, permissions) {
			c.JSON(http.StatusForbidden, gin.H{
				"error": "权限不足，需要至少一个指定权限",
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

// RequireRole 要求用户拥有特定角色
func RequireRole(role string) gin.HandlerFunc {
	return func(c *gin.Context) {
		// 获取用户角色
		roles := GetUserRolesFromContext(c)

		// 检查角色
		hasRole := false
		for _, r := range roles {
			if r == role {
				hasRole = true
				break
			}
		}

		if !hasRole {
			c.JSON(http.StatusForbidden, gin.H{
				"error": "权限不足，需要角色：" + role,
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

// RequireAdmin 要求用户是管理员
func RequireAdmin() gin.HandlerFunc {
	return RequireRole("admin")
}
