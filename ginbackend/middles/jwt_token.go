package middles

import (
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dgrijalva/jwt-go"
	"github.com/gin-gonic/gin"
)

// JWT配置结构
type JWTConfig struct {
	SecretKey       string        // 密钥（至少32字节）
	Expiration      time.Duration // Token有效期
	Issuer          string        // 签发者
	TokenHeader     string        // HTTP头部字段名
	TokenPrefix     string        // Token前缀
	BlacklistPrefix string        // 黑名单Redis前缀
}

// 自定义Claims结构
type CustomClaims struct {
	UserID       int    `json:"user_id"`
	Username     string `json:"username"`
	Role         int    `json:"role"`
	EnterpriseID int    `json:"enterprise_id"`
	jwt.StandardClaims
}

// Token存储接口（用于黑名单管理）
type TokenStorage interface {
	Set(key string, value interface{}, expiration time.Duration) error
	Exists(key string) (bool, error)
}

// TOKEN是无状态的 登出要维护黑名单 实际中使用数据库或者Redis存储
// 内存存储实现（用于开发/单机测试）
type MemoryStorage struct {
	mu   sync.RWMutex
	data map[string]time.Time
}

func NewMemoryStorage() *MemoryStorage {
	return &MemoryStorage{data: make(map[string]time.Time)}
}

func (m *MemoryStorage) Set(key string, value interface{}, expiration time.Duration) error {
	m.mu.Lock()
	m.data[key] = time.Now().Add(expiration)
	m.mu.Unlock()
	return nil
}

func (m *MemoryStorage) Exists(key string) (bool, error) {
	m.mu.RLock()
	exp, ok := m.data[key]
	m.mu.RUnlock()
	if !ok {
		return false, nil
	}
	if time.Now().After(exp) {
		m.mu.Lock()
		delete(m.data, key)
		m.mu.Unlock()
		return false, nil
	}
	return true, nil
}

// JWT中间件实例
type jwtMiddleware struct {
	config  JWTConfig
	storage TokenStorage
}

var (
	defaultConfig = JWTConfig{
		SecretKey:       "default_secret_key_at_least_32_bytes_123",
		Expiration:      2 * time.Hour,
		Issuer:          "default-app",
		TokenHeader:     "Authorization",
		TokenPrefix:     "Bearer",
		BlacklistPrefix: "jwt:blacklist",
	}
	globalMiddleware *jwtMiddleware
)

// 初始化全局JWT中间件
func InitJWTMiddleware(config JWTConfig, storage TokenStorage) {
	if config.SecretKey == "" {
		config.SecretKey = defaultConfig.SecretKey
	}
	if config.Expiration == 0 {
		config.Expiration = defaultConfig.Expiration
	}
	if config.Issuer == "" {
		config.Issuer = defaultConfig.Issuer
	}
	if config.TokenHeader == "" {
		config.TokenHeader = defaultConfig.TokenHeader
	}
	if config.TokenPrefix == "" {
		config.TokenPrefix = defaultConfig.TokenPrefix
	}
	if config.BlacklistPrefix == "" {
		config.BlacklistPrefix = defaultConfig.BlacklistPrefix
	}

	globalMiddleware = &jwtMiddleware{
		config:  config,
		storage: storage,
	}
}

// 获取JWT中间件HandlerFunc
func JWTMiddleware() gin.HandlerFunc {
	if globalMiddleware == nil {
		InitJWTMiddleware(JWTConfig{}, nil)
	}
	return globalMiddleware.middleware()
}

// 生成JWT Token
func GenerateToken(userID int, username string, role int, enterpriseID int) (string, error) {
	if globalMiddleware == nil {
		InitJWTMiddleware(JWTConfig{}, nil)
	}
	return globalMiddleware.generateToken(userID, username, role, enterpriseID)
}

// 注销Token
func RevokeToken(tokenString string) error {
	if globalMiddleware == nil {
		return errors.New("JWT middleware not initialized")
	}
	return globalMiddleware.revokeToken(tokenString)
}

// 从上下文中获取Claims
func GetClaims(c *gin.Context) (*CustomClaims, bool) {
	claims, exists := c.Get("jwt_claims")
	if !exists {
		return nil, false
	}

	customClaims, ok := claims.(*CustomClaims)
	return customClaims, ok
}

func (m *jwtMiddleware) generateToken(userID int, username string, role int, enterpriseID int) (string, error) {
	claims := CustomClaims{
		UserID:       userID,
		Username:     username,
		Role:         role,
		EnterpriseID: enterpriseID,
		StandardClaims: jwt.StandardClaims{
			ExpiresAt: time.Now().Add(m.config.Expiration).Unix(),
			IssuedAt:  time.Now().Unix(),
			Issuer:    m.config.Issuer,
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(m.config.SecretKey))
}

func (m *jwtMiddleware) verifyToken(tokenString string) (*CustomClaims, error) {
	if m.storage != nil {
		key := fmt.Sprintf("%s:%s", m.config.BlacklistPrefix, tokenString)
		if exists, _ := m.storage.Exists(key); exists {
			return nil, errors.New("token revoked")
		}
	}

	token, err := jwt.ParseWithClaims(tokenString, &CustomClaims{}, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		return []byte(m.config.SecretKey), nil
	})
	if err != nil {
		return nil, err
	}

	if claims, ok := token.Claims.(*CustomClaims); ok && token.Valid {
		return claims, nil
	}
	return nil, errors.New("invalid token")
}

func (m *jwtMiddleware) middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		tokenHeader := c.GetHeader(m.config.TokenHeader)
		if tokenHeader == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing authorization header"})
			return
		}

		tokenString := strings.TrimPrefix(tokenHeader, m.config.TokenPrefix+" ")
		if tokenString == tokenHeader {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "malformed token format"})
			return
		}

		claims, err := m.verifyToken(tokenString)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token", "reason": err.Error()})
			return
		}

		c.Set("jwt_claims", claims)
		c.Next()
	}
}

func (m *jwtMiddleware) revokeToken(tokenString string) error {
	if m.storage == nil {
		return errors.New("storage not initialized")
	}

	claims, _, err := new(jwt.Parser).ParseUnverified(tokenString, &CustomClaims{})
	if err != nil {
		return err
	}

	exp := time.Unix(claims.Claims.(*CustomClaims).ExpiresAt, 0)
	ttl := time.Until(exp)

	if ttl > 0 {
		key := fmt.Sprintf("%s:%s", m.config.BlacklistPrefix, tokenString)
		return m.storage.Set(key, true, ttl)
	}

	return nil
}

func GetEnterpriseIDFromClaims(claims *CustomClaims) string {
	return strconv.Itoa(claims.EnterpriseID)
}
