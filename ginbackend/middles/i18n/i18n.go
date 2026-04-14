// i18n/i18n.go
package i18n

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/nicksnyder/go-i18n/v2/i18n"
	"golang.org/x/text/language"
)

// I18n 是我们的国际化管理器
type I18n struct {
	bundle       *i18n.Bundle
	localizer    *i18n.Localizer
	defaultLang  string
	fallbackLang string
}

// New 创建一个新的I18n实例
func New(defaultLang, fallbackLang, localesPath string) (*I18n, error) {

	// 创建一个新的bundle，以defaultLang作为默认语言
	bundle := i18n.NewBundle(language.Make(defaultLang))

	// 设置JSON解码器
	bundle.RegisterUnmarshalFunc("json", json.Unmarshal)

	// 加载翻译文件
	err := loadTranslationFiles(bundle, localesPath)
	if err != nil {

		return nil, err
	}

	// 创建本地化器
	localizer := i18n.NewLocalizer(bundle, defaultLang, fallbackLang)

	return &I18n{

		bundle:       bundle,
		localizer:    localizer,
		defaultLang:  defaultLang,
		fallbackLang: fallbackLang,
	}, nil
}

// loadTranslationFiles 加载指定目录下的所有翻译文件
func loadTranslationFiles(bundle *i18n.Bundle, path string) error {

	files, err := os.ReadDir(path)
	if err != nil {

		return err
	}

	for _, file := range files {

		if file.IsDir() {

			continue
		}

		// 只处理JSON文件
		if !strings.HasSuffix(file.Name(), ".json") {

			continue
		}

		// 加载翻译文件
		_, err := bundle.LoadMessageFile(filepath.Join(path, file.Name()))
		if err != nil {

			return err
		}
	}

	return nil
}

// SetLanguage 设置当前语言
func (i *I18n) SetLanguage(langs ...string) {

	i.localizer = i18n.NewLocalizer(i.bundle, langs...)
}

// T 翻译一个消息
func (i *I18n) T(messageID string, templateData map[string]interface {
}) string {

	msg, err := i.localizer.Localize(&i18n.LocalizeConfig{

		// 消息ID
		MessageID: messageID,
		// 传递内容
		TemplateData: templateData,
	})

	if err != nil {

		return messageID // 如果翻译失败，返回原始消息ID
	}

	return msg
}

// Tn 翻译一个带有复数形式的消息
func (i *I18n) Tn(messageID string, count int, templateData map[string]interface {
}) string {

	// 如果templateData为nil，创建一个新的map
	if templateData == nil {

		templateData = make(map[string]interface {
		})
	}

	// 添加count到templateData
	templateData["Count"] = count

	msg, err := i.localizer.Localize(&i18n.LocalizeConfig{

		MessageID:    messageID,
		PluralCount:  count,
		TemplateData: templateData,
	})

	if err != nil {

		return fmt.Sprintf("%s (%d)", messageID, count) // 如果翻译失败，返回原始消息ID和count
	}

	return msg
}
