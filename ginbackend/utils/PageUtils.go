package utils

import (
	"strconv"

	"github.com/gin-gonic/gin"
)

// 获取分页参数
func GetPagination(c *gin.Context) (page, pageSize int) {
	// 从查询参数中获取page和page_size，默认为1和10
	// 获取?后面的参数
	// Atoi字符串转整数
	page, _ = strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ = strconv.Atoi(c.DefaultQuery("page_size", "10"))

	// 限制页面大小，防止请求过大数据
	if pageSize > 100 {
		pageSize = 100
	}

	// 确保page至少为1
	if page < 1 {
		page = 1
	}

	return page, pageSize
}
