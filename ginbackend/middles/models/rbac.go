package models

// Permission 权限
type Permission string //把string  更名为权限

// User 用户结构体
type User struct {
	ID    uint     `json:"id"`
	Name  string   `json:"name"`
	Roles []string `json:"roles"`
}

// Users 模拟用户数据
var Users = []User{
	{ID: 1, Name: "dada", Roles: []string{"admin"}},
	{ID: 2, Name: "jizhe", Roles: []string{"editor"}},
	{ID: 3, Name: "yong", Roles: []string{"user"}},
	{ID: 4, Name: "laibing", Roles: []string{"guest"}},
}

// 定义系统权限
const (
	PermReadUsers   Permission = "read:users"
	PermCreateUsers Permission = "create:users"
	PermUpdateUsers Permission = "update:users"
	PermDeleteUsers Permission = "delete:users"

	PermReadPosts   Permission = "read:posts"
	PermCreatePosts Permission = "create:posts"
	PermUpdatePosts Permission = "update:posts"
	PermDeletePosts Permission = "delete:posts"

	PermReadSettings   Permission = "read:settings"
	PermUpdateSettings Permission = "update:settings"
)

// RoleMap 角色及其关联的权限
var RoleMap = map[string][]Permission{
	"admin": {
		PermReadUsers, PermCreateUsers, PermUpdateUsers, PermDeleteUsers,
		PermReadPosts, PermCreatePosts, PermUpdatePosts, PermDeletePosts,
		PermReadSettings, PermUpdateSettings,
	},
	"editor": {
		PermReadUsers,
		PermReadPosts, PermCreatePosts, PermUpdatePosts,
		PermReadSettings,
	},
	"user": {
		PermReadPosts, PermCreatePosts,
		PermReadSettings,
	},
	"guest": {
		PermReadPosts,
	},
}

// GetUserPermissions 获取用户的所有权限
func GetUserPermissions(roles []string) map[Permission]bool {
	permissions := make(map[Permission]bool)

	// 遍历用户的所有角色
	for _, role := range roles {
		// 获取该角色的权限
		//获取值,值是否存在
		rolePermissions, exists := RoleMap[role]
		//不加判断的话 可能值是nil 但是无法区分值是nil还是key不存在
		if !exists {
			continue
		}

		// 添加该角色的所有权限
		for _, perm := range rolePermissions {
			permissions[perm] = true
		}
	}

	return permissions
}

// HasPermission 检查用户是否拥有特定权限
func HasPermission(roles []string, permission Permission) bool {
	permissions := GetUserPermissions(roles)
	return permissions[permission]
}

// HasAllPermissions 检查用户是否拥有所有指定权限
func HasAllPermissions(roles []string, requiredPermissions []Permission) bool {
	userPermissions := GetUserPermissions(roles)

	// 检查每个所需权限
	for _, perm := range requiredPermissions {
		if !userPermissions[perm] {
			return false
		}
	}

	return true
}

// HasAnyPermission 检查用户是否拥有任意一个指定权限
func HasAnyPermission(roles []string, requiredPermissions []Permission) bool {
	userPermissions := GetUserPermissions(roles)

	// 检查是否有任何一个权限匹配
	for _, perm := range requiredPermissions {
		if userPermissions[perm] {
			return true
		}
	}

	return len(requiredPermissions) == 0 // 如果没有要求任何权限，则默认返回true
}
