package dao

import (
	"gobackend/project/config"
	"gobackend/project/models"
)

// 查询所有用户函数
func GetAllUsers() ([]models.User, error) {
	var users []models.User
	err := config.DB.Find(&users).Error
	if err != nil {
		return nil, err
	}
	return users, nil
}

func GetPageUserV1(page, pageSize, offset int) ([]models.User, error) {
	var users []models.User
	err := config.DB.
		Offset(offset).
		Limit(pageSize).
		Find(&users).
		Error
	if err != nil {
		return nil, err
	}
	return users, nil
}

// 根据ID查询用户
func GetUserByID(id uint) (*models.User, error) {
	var user models.User
	err := config.DB.First(&user, id).Error
	if err != nil {
		return nil, err
	}
	return &user, nil
}

// 根据用户名查询用户
func GetUserByUsername(username string) (*models.User, error) {
	var user models.User
	err := config.DB.Where("username = ?", username).First(&user).Error
	if err != nil {
		return nil, err
	}
	return &user, nil
}

// 查询企业所有者
func GetOwnerByEnterpriseID(enterpriseID int) (*models.User, error) {
	var user models.User
	err := config.DB.
		Where("enterprise_id = ? AND role = ?", enterpriseID, 0).
		First(&user).
		Error
	if err != nil {
		return nil, err
	}
	return &user, nil
}

// 查询企业待审核用户
func GetPendingUsersByEnterpriseID(enterpriseID int) ([]models.User, error) {
	var users []models.User
	err := config.DB.
		Where("enterprise_id = ? AND login_status = ?", enterpriseID, 2).
		Order("id desc").
		Find(&users).
		Error
	if err != nil {
		return nil, err
	}
	return users, nil
}

// 创建用户
func CreateUser(user *models.User) error {
	return config.DB.Create(user).Error
}

// 更新用户
func UpdateUser(user *models.User) error {
	return config.DB.Save(user).Error
}

// 软删除用户
func DeleteUser(id uint) error {
	return config.DB.Delete(&models.User{}, id).Error
}

// 硬删除用户
func HardDeleteUser(id uint) error {
	return config.DB.Unscoped().Delete(&models.User{}, id).Error
}

// 更新用户指定字段
func UpdateUserFields(id uint, updateData map[string]interface{}) error {
	return config.DB.Model(&models.User{}).
		Where("id = ?", id).
		Updates(updateData).
		Error
}
