package controller

import (
	"gobackend/project/services"
	"gobackend/utils"
	"net/http"

	"github.com/gin-gonic/gin"
)

type applyEnterpriseRequest struct {
	EnterpriseID    int    `json:"enterprise_id"`
	EnterpriseName  string `json:"enterprise_name"`
	Description     string `json:"description"`
	Logo            string `json:"logo"`
	Username        string `json:"username"`
	Password        string `json:"password"`
	ConfirmPassword string `json:"confirm_password"`
	Nick            string `json:"nick"`
}

func ApplyEnterpriseV1(c *gin.Context) {
	var req applyEnterpriseRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "请求数据格式错误")
		return
	}

	if req.Password != req.ConfirmPassword {
		utils.HttpErrorResponse(c, http.StatusBadRequest, "密码和确认密码不一致")
		return
	}

	result, err := services.ApplyEnterprise(&services.ApplyEnterpriseRequest{
		EnterpriseID:   req.EnterpriseID,
		EnterpriseName: req.EnterpriseName,
		Description:    req.Description,
		Logo:           req.Logo,
		Username:       req.Username,
		Password:       req.Password,
		Nick:           req.Nick,
	})
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, result)
}

func ListEnterpriseV1(c *gin.Context) {
	result, err := services.ListEnterprise()
	if err != nil {
		utils.HttpErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}
	utils.SuccessResponse(c, result)
}
