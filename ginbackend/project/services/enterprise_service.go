package services

import (
	"errors"
	"gobackend/middles"
	"gobackend/project/config"
	"gobackend/project/dao"
	"gobackend/project/models"
	"time"

	"gorm.io/gorm"
)

type ApplyEnterpriseRequest struct {
	EnterpriseID   int
	EnterpriseName string
	Description    string
	Logo           string
	Username       string
	Password       string
	Nick           string
}

type ApplyEnterpriseResult struct {
	Token      string `json:"token"`
	Enterprise uint   `json:"enterprise_id"`
	Username   string `json:"username"`
}

func ApplyEnterprise(req *ApplyEnterpriseRequest) (*ApplyEnterpriseResult, error) {
	if req.EnterpriseName == "" {
		return nil, errors.New("企业名称不能为空")
	}
	if req.Username == "" {
		return nil, errors.New("用户名不能为空")
	}
	if req.Password == "" {
		return nil, errors.New("密码不能为空")
	}

	if _, err := dao.GetUserByUsername(req.Username); err == nil {
		return nil, errors.New("用户名已存在")
	}

	enterprise := &models.Enterprise{
		Name:        req.EnterpriseName,
		Description: req.Description,
		Logo:        req.Logo,
	}

	owner := &models.User{
		Username:    req.Username,
		Password:    hashPassword(req.Password),
		Nick:        req.Nick,
		Role:        0,
		LoginStatus: 1,
		CreatedAt:   time.Now().Format("2006-01-02 15:04:05"),
		UpdatedAt:   time.Now(),
	}

	err := config.DB.Transaction(func(tx *gorm.DB) error {
		if err := dao.CreateEnterpriseWithTx(tx, enterprise); err != nil {
			return err
		}

		owner.EnterpriseID = int(enterprise.ID)
		if err := tx.Create(owner).Error; err != nil {
			return err
		}
		return nil
	})
	if err != nil {
		return nil, err
	}

	token, err := middles.GenerateToken(int(owner.ID), owner.Username, owner.Role, owner.EnterpriseID)
	if err != nil {
		return nil, err
	}

	return &ApplyEnterpriseResult{
		Token:      token,
		Enterprise: enterprise.ID,
		Username:   owner.Username,
	}, nil
}

func ListEnterprise() ([]models.Enterprise, error) {
	return dao.GetEnterprises()

}
