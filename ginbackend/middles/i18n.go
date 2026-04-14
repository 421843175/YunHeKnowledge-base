package middles

import (
	"gobackend/middles/i18n"
	"strings"

	"github.com/gin-gonic/gin"
)

const (
	// 默认语言
	defaultLang = "en"
	// 备用语言
	fallbackLang = "en"
	// Cookie名称
	langCookieName = "lang"
	// 查询参数名称
	langQueryParam = "lang"
	// 会话键名称
	langContextKey = "language"
)

// I18nMiddleware 创建一个处理国际化的中间件
func I18nMiddleware(i18nManager *i18n.I18n) gin.HandlerFunc {
	// 参数 ?lang= 的会保存到cookie中 下次不传 默认用cookie中的
	// Accept-Language 头则不会保存到cookie中 每次请求都需要传递

	return func(c *gin.Context) {

		// 1. 尝试从查询参数获取语言
		lang := c.Query(langQueryParam)

		// 2. 如果查询参数中没有，尝试从Cookie获取
		if lang == "" {

			if langCookie, err := c.Cookie(langCookieName); err == nil {

				lang = langCookie
			}
		}

		// 3. 如果Cookie中也没有，尝试从Accept-Language头获取
		if lang == "" {

			acceptLanguage := c.GetHeader("Accept-Language")
			lang = parseAcceptLanguage(acceptLanguage)
		}

		// 4. 如果都没有，使用默认语言
		if lang == "" {

			lang = defaultLang
		}

		// 设置当前会话的语言
		i18nManager.SetLanguage(lang, fallbackLang)

		// 将语言和i18n管理器存储在上下文中，以便在处理程序中使用
		c.Set(langContextKey, lang)
		c.Set("i18n", i18nManager)

		// 如果是通过查询参数设置的语言，将其存储在Cookie中以备将来使用
		if c.Query(langQueryParam) != "" {

			c.SetCookie(langCookieName, lang, 86400*30, "/", "", false, false)
		}

		c.Next()
	}
}

// parseAcceptLanguage 解析Accept-Language头，返回最优先的语言
func parseAcceptLanguage(acceptLanguage string) string {

	if acceptLanguage == "" {

		return ""
	}

	// 简单实现：只获取第一个语言代码
	parts := strings.Split(acceptLanguage, ",")
	if len(parts) == 0 {

		return ""
	}

	// 提取语言代码（可能包含权重，如zh-CN;q=0.9）
	langWithWeight := strings.Split(parts[0], ";")
	lang := strings.TrimSpace(langWithWeight[0])

	// 如果是形如zh-CN的格式，只取主要部分zh
	if strings.Contains(lang, "-") {

		return strings.Split(lang, "-")[0]
	}

	return lang
}

// GetI18n 从上下文中获取i18n管理器
func GetI18n(c *gin.Context) *i18n.I18n {

	if i18nValue, exists := c.Get("i18n"); exists {

		if i18n, ok := i18nValue.(*i18n.I18n); ok {

			return i18n
		}
	}
	return nil
}

// GetLanguage 从上下文中获取当前语言
func GetLanguage(c *gin.Context) string {

	if langValue, exists := c.Get(langContextKey); exists {

		if lang, ok := langValue.(string); ok {

			return lang
		}
	}
	return defaultLang
}
