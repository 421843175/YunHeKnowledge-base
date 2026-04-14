package middles

import (
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gomodule/redigo/redis"
)

var redisPool *redis.Pool

func initRedisPool() {
	if redisPool != nil {
		return
	}
	redisPool = &redis.Pool{
		MaxIdle:     10,
		MaxActive:   50,
		IdleTimeout: 300 * time.Second,
		Wait:        true,
		Dial: func() (redis.Conn, error) {
			return redis.Dial("tcp", "127.0.0.1:6379", redis.DialPassword("123456"))
		},
		TestOnBorrow: func(c redis.Conn, t time.Time) error {
			if time.Since(t) < time.Minute {
				return nil
			}
			_, err := c.Do("PING")
			return err
		},
	}
}

func RedisMiddleware() gin.HandlerFunc {
	initRedisPool()
	return func(c *gin.Context) {
		conn := redisPool.Get()
		c.Set("redis", conn)
		defer conn.Close()
		c.Next()
	}
}

func GetRedisConn() redis.Conn {
	initRedisPool()
	return redisPool.Get()
}

func GetRedis(c *gin.Context) (redis.Conn, bool) {
	v, ok := c.Get("redis")
	if !ok {
		return nil, false
	}
	conn, ok := v.(redis.Conn)
	return conn, ok
}
