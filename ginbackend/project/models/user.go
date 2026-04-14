package models

import "time"

// User 用户模型
// password: bcrypt哈希
// 角色说明：
// 0 = 所有者：一个企业只能有一个所有者
// 1 = 管理员：可管理企业信息、上传文档进入知识库；注册后需要所有者审核
// 2 = 职工：注册需要管理员或者所有者审核
// login_status 说明：
// 0 = 审核未通过
// 1 = 审核通过
// 2 = 待审核
// 常见流程：
// 所有者通常可直接通过；管理员注册后需所有者审核；职工注册后需管理员或所有者审核。
type User struct {
	ID           uint      `gorm:"primaryKey;column:id" json:"id"`
	Username     string    `gorm:"column:username;type:varchar(255)" json:"username"`
	Password     string    `gorm:"column:password;type:varchar(255);comment:bcrypt哈希" json:"password"`
	Nick         string    `gorm:"column:nick;type:varchar(255)" json:"nick"`
	EnterpriseID int       `gorm:"column:enterprise_id;comment:所属企业id" json:"enterprise_id"`
	Role         int       `gorm:"column:role;comment:角色 0是所有者 1是管理员 2是职工" json:"role"`
	LoginStatus  int       `gorm:"column:login_status;comment:注册是否通过 1审核通过 0审核未通过 2待审核" json:"login_status"`
	CreatedAt    string    `gorm:"column:created_at;type:varchar(255)" json:"created_at"`
	UpdatedAt    time.Time `gorm:"column:updated_at" json:"updated_at"`
	DeletedAt    string    `gorm:"column:deleted_at;type:varchar(255)" json:"deleted_at"`
}

func (User) TableName() string {
	return "user"
}
