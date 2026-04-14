package config

import (
	"fmt"
	"gobackend/project/models"
	"log"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// public DB 数据库连接实例
var DB *gorm.DB

// InitDB 初始化数据库连接
func InitDB() {
	// 数据库连接配置
	username := "root"
	password := "0000"
	host := "localhost"
	port := 3306
	dbname := "knowledgebaseuser"

	// 构建DSN连接字符串
	dsn := fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?charset=utf8mb4&parseTime=True&loc=Local",
		username, password, host, port, dbname)

	// 配置GORM
	config := &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info), // 设置日志级别
	}

	// 连接数据库
	var err error
	DB, err = gorm.Open(mysql.Open(dsn), config)
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}

	log.Println("Database connected successfully")

	// 自动迁移数据库表
	err = DB.AutoMigrate(&models.User{}, &models.Enterprise{})
	if err != nil {
		log.Fatalf("Failed to migrate database: %v", err)
	}

	log.Println("Database migration completed successfully")
}
