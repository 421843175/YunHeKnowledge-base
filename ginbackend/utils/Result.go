package utils

import "github.com/gin-gonic/gin"

// 自定义错误
//这里必须要用大写 因为go语言小写是private
type Response struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data"`
}

func (e *Response) Error() string {
	return e.Message
}

// 成功响应
func SuccessResponse(c *gin.Context, data interface{}) {
	c.JSON(200, Response{
		Code:    200,
		Message: "success",
		Data:    data,
	})
}

// 错误响应
func ErrorResponse(c *gin.Context, message string) {
	c.JSON(403, Response{
		Code:    403,
		Message: message,
	})
}

// 错误响应
func HttpErrorResponse(c *gin.Context, httpcode int, message string) {
	c.JSON(httpcode, Response{
		Code:    httpcode,
		Message: message,
	})
}
