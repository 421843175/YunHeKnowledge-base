package fileutils

import (
	"bufio"
	"fmt"
	"gobackend/utils"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// 读取文件前charCount个字符
func ReadFileContent(f io.Reader, charCount int) (string, error) {
	reader := bufio.NewReader(f)
	var chars []rune
	for i := 0; i < charCount; i++ {
		r, _, err := reader.ReadRune()
		if err != nil {
			if err == io.EOF {
				break
			}
			return "", err
		}
		chars = append(chars, r)
	}
	return string(chars), nil
}

// 上传多个文件
func UploadsFiles(c *gin.Context, files []*multipart.FileHeader, uploadDir string) (filenames []string, err error) {

	for _, file := range files {
		// 自定义文件名
		filename := fmt.Sprintf("%d_%s", time.Now().UnixNano(), file.Filename)
		dst := filepath.Join(uploadDir, filename)

		// 保存文件
		if err := c.SaveUploadedFile(file, dst); err != nil {
			return nil, err
		}

		filenames = append(filenames, filename)
	}

	return filenames, nil
}

// 分块上传文件
func ChunkUpload(c *gin.Context, file *multipart.FileHeader, uploadDir string) (filename string, err error) {
	// 打开上传的文件
	src, err := file.Open()
	if err != nil {
		utils.ErrorResponse(c, "打开文件失败")
		return "", err
	}
	defer src.Close()

	dst, err := os.Create(filepath.Join(uploadDir, file.Filename))
	if err != nil {
		utils.ErrorResponse(c, "创建文件失败")
		return "", err
	}
	defer dst.Close()

	//复制文件内容
	if _, err := io.Copy(dst, src); err != nil {
		utils.ErrorResponse(c, "复制文件失败")
		return "", err
	}

	return file.Filename, nil
}

func PreviewFile(c *gin.Context, filename string, uploadDir string) (err error) {
	filePath := filepath.Join(uploadDir, filename)
	file, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer file.Close()

	// 根据文件扩展名设置正确的 Content-Type
	ext := strings.ToLower(filepath.Ext(filename))
	var contentType string
	switch ext {
	case ".jpg", ".jpeg":
		contentType = "image/jpeg"
	case ".png":
		contentType = "image/png"
	case ".gif":
		contentType = "image/gif"
	case ".pdf":
		contentType = "application/pdf"
	case ".txt":
		contentType = "text/plain; charset=utf-8"
	case ".html", ".htm":
		contentType = "text/html; charset=utf-8"
	case ".css":
		contentType = "text/css"
	case ".js":
		contentType = "application/javascript"
	case ".json":
		contentType = "application/json"
	case ".xml":
		contentType = "application/xml"
	case ".doc", ".docx":
		contentType = "application/msword"
	default:
		contentType = "application/octet-stream"
	}

	// 设置响应头
	c.Header("Content-Type", contentType)
	c.Header("Content-Disposition", fmt.Sprintf("inline; filename=\"%s\"", filename))

	// 复制文件内容到响应
	if _, err = io.Copy(c.Writer, file); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "复制文件内容失败"})
		return err
	}
	return
}
