package dao

import (
	"gobackend/project/config"
	"gobackend/project/models"

	"gorm.io/gorm"
)

func CreateEnterprise(enterprise *models.Enterprise) error {
	return config.DB.Create(enterprise).Error
}

func GetEnterpriseByID(id uint) (*models.Enterprise, error) {
	var enterprise models.Enterprise
	err := config.DB.First(&enterprise, id).Error
	if err != nil {
		return nil, err
	}
	return &enterprise, nil
}
func GetEnterprises() ([]models.Enterprise, error) {
	var enterprises []models.Enterprise
	err := config.DB.Find(&enterprises).Error
	if err != nil {
		return nil, err
	}
	return enterprises, nil
}

func CreateEnterpriseWithTx(tx *gorm.DB, enterprise *models.Enterprise) error {
	return tx.Create(enterprise).Error
}
