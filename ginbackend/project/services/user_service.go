package services

import (
	"errors"
	"gobackend/project/dao"
	"gobackend/project/models"
	"log"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// GetAllUsers 获取所有用户的服务函数
func GetAllUsers() ([]models.User, error) {
	return dao.GetAllUsers()
}

// GetUserByID 根据ID获取用户
func GetUserByID(id uint) (*models.User, error) {
	if id == 0 {
		return nil, errors.New("用户ID不能为空")
	}
	return dao.GetUserByID(id)
}

func GetPageUserV1(page, pageSize, offset int) ([]models.User, error) {
	return dao.GetPageUserV1(page, pageSize, offset)
}

// RegisterUser 注册用户
func RegisterUser(user *models.User) error {
	if user.Username == "" {
		return errors.New("用户名不能为空")
	}
	if user.Password == "" {
		return errors.New("密码不能为空")
	}
	if user.Nick == "" {
		return errors.New("昵称不能为空")
	}
	if user.Role == 0 {
		if _, err := dao.GetOwnerByEnterpriseID(user.EnterpriseID); err == nil {
			return errors.New("一个企业只能有一个所有者")
		}
	}

	_, err := dao.GetUserByUsername(user.Username)
	if err == nil {
		return errors.New("用户名已存在")
	}

	user.Password = hashPassword(user.Password)
	user.CreatedAt = time.Now().Format("2006-01-02 15:04:05")
	user.UpdatedAt = time.Now()

	if user.Role == 0 {
		user.LoginStatus = 1
	} else {
		user.LoginStatus = 2
	}

	return dao.CreateUser(user)
}

// LoginUser 用户登录
func LoginUser(username, password string) (*models.User, error) {
	if username == "" {
		return nil, errors.New("用户名不能为空")
	}
	if password == "" {
		return nil, errors.New("密码不能为空")
	}

	user, err := dao.GetUserByUsername(username)
	if err != nil {
		return nil, errors.New("用户名或密码错误")
	}

	if user.LoginStatus != 1 {
		return nil, errors.New("当前账号未审核通过")
	}

	if !checkPassword(user.Password, password) {
		return nil, errors.New("用户名或密码错误")
	}

	return user, nil
}

// GetPendingUsers 获取当前企业待审核用户
func GetPendingUsers(operatorID uint) ([]models.User, error) {
	operator, err := dao.GetUserByID(operatorID)
	if err != nil {
		return nil, errors.New("当前用户不存在")
	}
	if operator.LoginStatus != 1 {
		return nil, errors.New("当前账号未审核通过")
	}
	if operator.Role != 0 && operator.Role != 1 {
		return nil, errors.New("当前用户无审核权限")
	}

	users, err := dao.GetPendingUsersByEnterpriseID(operator.EnterpriseID)
	if err != nil {
		return nil, err
	}

	result := make([]models.User, 0, len(users))
	for _, user := range users {
		if canAuditUser(operator.Role, user.Role) {
			user.Password = ""
			result = append(result, user)
		}
	}
	return result, nil
}

// AuditUser 审核用户
func AuditUser(operatorID, targetUserID uint, loginStatus int) error {
	if loginStatus != 0 && loginStatus != 1 {
		return errors.New("审核状态只能是0或1")
	}

	operator, err := dao.GetUserByID(operatorID)
	if err != nil {
		return errors.New("当前用户不存在")
	}
	if operator.LoginStatus != 1 {
		return errors.New("当前账号未审核通过")
	}
	if operator.Role != 0 && operator.Role != 1 {
		return errors.New("当前用户无审核权限")
	}

	targetUser, err := dao.GetUserByID(targetUserID)
	if err != nil {
		return errors.New("待审核用户不存在")
	}
	if targetUser.EnterpriseID != operator.EnterpriseID {
		return errors.New("只能审核本企业用户")
	}
	if targetUser.LoginStatus != 2 {
		return errors.New("该用户当前不是待审核状态")
	}
	if !canAuditUser(operator.Role, targetUser.Role) {
		return errors.New("当前用户无权限审核该申请")
	}

	return dao.UpdateUserFields(targetUserID, map[string]interface{}{
		"login_status": loginStatus,
		"updated_at":   time.Now(),
	})
}

// CreateUser 创建用户
func CreateUser(user *models.User) error {
	return RegisterUser(user)
}

// UpdateUser 更新用户
func UpdateUser(id uint, user *models.User) error {
	log.Printf("user::::::::user=%v", user)
	if id == 0 {
		return errors.New("用户ID不能为空")
	}

	existingUser, err := dao.GetUserByID(id)
	if err != nil {
		return errors.New("用户不存在")
	}

	existingUser.Username = user.Username
	existingUser.Nick = user.Nick
	existingUser.EnterpriseID = user.EnterpriseID
	existingUser.Role = user.Role
	existingUser.LoginStatus = user.LoginStatus
	if user.Password != "" {
		existingUser.Password = hashPassword(user.Password)
	}
	existingUser.UpdatedAt = time.Now()

	return dao.UpdateUser(existingUser)
}

// UpdateUserFields 选择性更新用户字段
func UpdateUserFields(id uint, updateFields map[string]interface{}) error {
	if id == 0 {
		return errors.New("用户ID不能为空")
	}

	_, err := dao.GetUserByID(id)
	if err != nil {
		return errors.New("用户不存在")
	}

	if passwordValue, exists := updateFields["password"]; exists {
		password, ok := passwordValue.(string)
		if !ok {
			return errors.New("密码格式错误")
		}
		if password != "" {
			updateFields["password"] = hashPassword(password)
		}
	}

	updateFields["updated_at"] = time.Now()

	return dao.UpdateUserFields(id, updateFields)
}

// DeleteUser 软删除用户
func DeleteUser(id uint) error {
	if id == 0 {
		return errors.New("用户ID不能为空")
	}

	_, err := dao.GetUserByID(id)
	if err != nil {
		return errors.New("用户不存在")
	}

	return dao.DeleteUser(id)
}

// HardDeleteUser 硬删除用户
func HardDeleteUser(id uint) error {
	if id == 0 {
		return errors.New("用户ID不能为空")
	}

	_, err := dao.GetUserByID(id)
	if err != nil {
		return errors.New("用户不存在")
	}

	return dao.HardDeleteUser(id)
}

func hashPassword(value string) string {
	hash, err := bcrypt.GenerateFromPassword([]byte(value), bcrypt.DefaultCost)
	if err != nil {
		panic(err)
	}
	return string(hash)
}

func checkPassword(hashedPassword, plainPassword string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hashedPassword), []byte(plainPassword)) == nil
}

func canAuditUser(operatorRole, targetRole int) bool {
	if operatorRole == 0 {
		return targetRole == 1 || targetRole == 2
	}
	if operatorRole == 1 {
		return targetRole == 2
	}
	return false
}
