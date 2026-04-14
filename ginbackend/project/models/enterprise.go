package models

// Enterprise 企业模型
type Enterprise struct {
	ID          uint   `gorm:"primaryKey;column:id" json:"id"`
	Name        string `gorm:"column:name;type:varchar(255);comment:企业名" json:"name"`
	Description string `gorm:"column:description;type:varchar(255);comment:描述" json:"description"`
	Logo        string `gorm:"column:logo;type:varchar(255);comment:企业logo" json:"logo"`
}

func (Enterprise) TableName() string {
	return "enterprise"
}
