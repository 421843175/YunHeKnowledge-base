package utils

import (
	"errors"
	"time"
)

// parseDateField 解析和日期字段
func ParseDateField(birthdayValue interface{}) (time.Time, error) {
	var birthday time.Time

	switch v := birthdayValue.(type) {
	case string:
		// 支持多种日期格式
		formats := []string{
			time.RFC3339,          // "2006-01-02T15:04:05Z07:00"
			"2006-01-02",          // "2006-01-02"
			"2006-01-02 15:04:05", // "2006-01-02 15:04:05"
		}

		var parseErr error
		for _, format := range formats {
			birthday, parseErr = time.Parse(format, v)
			if parseErr == nil {
				break
			}
		}

		if parseErr != nil {
			return time.Time{}, errors.New("生日日期格式错误，支持格式: 2006-01-02 或 2006-01-02T15:04:05Z")
		}

	case time.Time:
		birthday = v

	default:
		return time.Time{}, errors.New("生日字段类型错误")
	}

	// 验证日期范围 (1900-2100)
	if birthday.Year() < 1900 || birthday.Year() > 2100 {
		return time.Time{}, errors.New("生日年份必须在1900-2100之间")
	}

	return birthday, nil
}
