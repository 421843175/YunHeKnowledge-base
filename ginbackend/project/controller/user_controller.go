package controller

import (
	"gobackend/middles"
	"gobackend/project/models"
	"gobackend/project/services"
	"gobackend/utils"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
)

type registerUserRequest struct {
	Username        string `json:"username"`
	Password        string `json:"password"`
	ConfirmPassword string `json:"confirm_password"`
	Nick            string `json:"nick"`
	EnterpriseID    int    `json:"enterprise_id"`
	Role            int    `json:"role"`
}

type loginUserRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type auditUserRequest struct {
	UserID      uint `json:"user_id"`
	LoginStatus int  `json:"login_status"`
}

// GetAllUsersV1 获取所有用户
func GetAllUsersV1(c *gin.Context) {
	users, err := services.GetAllUsers()
	if err != nil {
		utils.ErrorResponse(c, "获取用户列表失败")
		return
	}

	for i := range users {
		users[i].Password = ""
	}
	utils.SuccessResponse(c, users)
}

// GetUserV1 根据ID获取用户
func GetUserV1(c *gin.Context) {
	idStr := c.Param("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "无效的用户ID")
		return
	}

	user, err := services.GetUserByID(uint(id))
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusNotFound, "用户不存在")
		return
	}

	user.Password = ""
	utils.SuccessResponse(c, user)
}

// GetPageUserV1 分页获取用户
func GetPageUserV1(c *gin.Context) {
	page, pageSize := utils.GetPagination(c)
	offset := (page - 1) * pageSize
	users, err := services.GetPageUserV1(page, pageSize, offset)
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}
	for i := range users {
		users[i].Password = ""
	}
	utils.SuccessResponse(c, users)
}

// RegisterUserV1 注册用户
func RegisterUserV1(c *gin.Context) {
	var req registerUserRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "请求数据格式错误")
		return
	}

	if req.Password != req.ConfirmPassword {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "密码和确认密码不一致")
		return
	}

	user := models.User{
		Username:     req.Username,
		Password:     req.Password,
		Nick:         req.Nick,
		EnterpriseID: req.EnterpriseID,
		Role:         req.Role,
	}

	if err := services.RegisterUser(&user); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, "注册成功，等待管理员审核")
}

// LoginUserV1 登录
func LoginUserV1(c *gin.Context) {
	var req loginUserRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "请求数据格式错误")
		return
	}

	user, err := services.LoginUser(req.Username, req.Password)
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	token, err := middles.GenerateToken(int(user.ID), user.Username, user.Role, user.EnterpriseID)
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusInternalServerError, "生成token失败")
		return
	}

	utils.SuccessResponse(c, gin.H{"token": token})
}

// GetPendingAuditUsersV1 查看待审核信息
func GetPendingAuditUsersV1(c *gin.Context) {
	claims, ok := middles.GetClaims(c)
	if !ok {
		utils.HttpErrorResponse(c, http.StatusUnauthorized, "未获取到登录信息")
		return
	}

	users, err := services.GetPendingUsers(uint(claims.UserID))
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, users)
}

// AuditUserV1 审核用户申请
func AuditUserV1(c *gin.Context) {
	claims, ok := middles.GetClaims(c)
	if !ok {
		utils.HttpErrorResponse(c, http.StatusUnauthorized, "未获取到登录信息")
		return
	}

	var req auditUserRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "请求数据格式错误")
		return
	}

	if err := services.AuditUser(uint(claims.UserID), req.UserID, req.LoginStatus); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	message := "审核不通过"
	if req.LoginStatus == 1 {
		message = "审核通过"
	}
	utils.SuccessResponse(c, message)
}

// CreateUserV1 创建用户
func CreateUserV1(c *gin.Context) {
	var user models.User
	if err := c.ShouldBindJSON(&user); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "请求数据格式错误")
		return
	}

	if err := services.CreateUser(&user); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	user.Password = ""
	utils.SuccessResponse(c, user)
}

// UpdateUserV1 更新用户
func UpdateUserV1(c *gin.Context) {
	idStr := c.Param("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "无效的用户ID")
		return
	}

	var user models.User
	if err := c.ShouldBindJSON(&user); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "请求数据格式错误")
		return
	}

	if err := services.UpdateUser(uint(id), &user); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, "用户更新成功")
}

// DeleteUserV1 软删除用户
func DeleteUserV1(c *gin.Context) {
	idStr := c.Param("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "无效的用户ID")
		return
	}

	if err := services.DeleteUser(uint(id)); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, "用户删除成功")
}

// HardDeleteUserV1 硬删除用户
func HardDeleteUserV1(c *gin.Context) {
	idStr := c.Param("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "无效的用户ID")
		return
	}

	if err := services.HardDeleteUser(uint(id)); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, "用户永久删除成功")
}

// UpdateUserFieldsV1 选择性更新用户字段
func UpdateUserFieldsV1(c *gin.Context) {
	idStr := c.Param("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "无效的用户ID")
		return
	}

	var updateFields map[string]interface{}
	if err := c.ShouldBindJSON(&updateFields); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "请求数据格式错误")
		return
	}

	if confirmPasswordValue, exists := updateFields["confirm_password"]; exists {
		confirmPassword, ok := confirmPasswordValue.(string)
		if !ok {
			utils.HttpErrorResponse(c, http.StatusBadRequest, "确认密码格式错误")
			return
		}
		password, _ := updateFields["password"].(string)
		if password != confirmPassword {
			utils.HttpErrorResponse(c, http.StatusBadRequest, "密码和确认密码不一致")
			return
		}
		delete(updateFields, "confirm_password")
	}

	if err := services.UpdateUserFields(uint(id), updateFields); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	updatedUser, err := services.GetUserByID(uint(id))
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusInternalServerError, "获取更新后的用户信息失败")
		return
	}

	updatedUser.Password = ""
	utils.SuccessResponse(c, updatedUser)
}
