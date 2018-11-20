package me.egg82.tfaplus.services;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.egg82.tfaplus.core.AuthyData;
import me.egg82.tfaplus.core.LoginData;
import me.egg82.tfaplus.core.SQLFetchResult;
import me.egg82.tfaplus.utils.RedisUtil;
import me.egg82.tfaplus.utils.ValidationUtil;
import ninja.leaping.configurate.ConfigurationNode;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;

public class Redis {
    private static final Logger logger = LoggerFactory.getLogger(Redis.class);

    private static final UUID serverId = UUID.randomUUID();
    public static UUID getServerID() { return serverId; }

    private Redis() {}

    public static CompletableFuture<Boolean> updateFromQueue(SQLFetchResult sqlResult, long ipTime, JedisPool pool, ConfigurationNode redisConfigNode) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis redis = RedisUtil.getRedis(pool, redisConfigNode)) {
                if (redis == null) {
                    return Boolean.FALSE;
                }

                for (String key : sqlResult.getRemovedKeys()) {
                    redis.del(key);
                    if (key.indexOf('|') == -1 && ValidationUtil.isValidUuid(key.substring(key.lastIndexOf(':') + 1))) {
                        redis.publish("2faplus-delete", key.substring(key.lastIndexOf(':') + 1));
                    }
                }

                for (LoginData result : sqlResult.getLoginData()) {
                    String key = "2faplus:login:" + result.getUUID() + "|" + result.getIP();
                    int offset = (int) Math.floorDiv((ipTime + result.getCreated()) - System.currentTimeMillis(), 1000L);
                    if (offset > 0) {
                        redis.setex(key, offset, String.valueOf(Boolean.TRUE));
                    } else {
                        redis.del(key);
                    }

                    String ipKey = "2faplus:ip:" + result.getIP();
                    if (offset > 0) {
                        redis.sadd(ipKey, result.getUUID().toString());
                    } else {
                        redis.srem(ipKey, result.getUUID().toString());
                    }

                    String uuidKey = "2faplus:uuid:" + result.getUUID();
                    if (offset > 0) {
                        redis.sadd(uuidKey, result.getIP());
                    } else {
                        redis.srem(uuidKey, result.getIP());
                    }

                    if (offset > 0) {
                        JSONObject obj = new JSONObject();
                        obj.put("uuid", result.getUUID().toString());
                        obj.put("ip", result.getIP());
                        obj.put("created", result.getCreated());
                        redis.publish("2faplus-login", obj.toJSONString());
                    } else {
                        redis.publish("2faplus-delete", result.getUUID().toString());
                    }
                }

                for (AuthyData result : sqlResult.getAuthyData()) {
                    String key = "2faplus:authy:" + result.getUUID();
                    redis.set(key, String.valueOf(result.getID()));

                    JSONObject obj = new JSONObject();
                    obj.put("uuid", result.getUUID().toString());
                    obj.put("id", result.getID());
                    redis.publish("2faplus-authy", obj.toJSONString());
                }

                return Boolean.TRUE;
            } catch (JedisException ex) {
                logger.error(ex.getMessage(), ex);
            }

            return Boolean.FALSE;
        });
    }

    public static CompletableFuture<Boolean> update(LoginData sqlResult, long ipTime, JedisPool pool, ConfigurationNode redisConfigNode) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis redis = RedisUtil.getRedis(pool, redisConfigNode)) {
                if (redis == null) {
                    return Boolean.FALSE;
                }

                String key = "2faplus:login:" + sqlResult.getUUID() + "|" + sqlResult.getIP();
                int offset = (int) Math.floorDiv((ipTime + sqlResult.getCreated()) - System.currentTimeMillis(), 1000L);
                if (offset > 0) {
                    redis.setex(key, offset, String.valueOf(Boolean.TRUE));
                } else {
                    redis.del(key);
                }

                String ipKey = "2faplus:ip:" + sqlResult.getIP();
                if (offset > 0) {
                    redis.sadd(ipKey, sqlResult.getUUID().toString());
                } else {
                    redis.srem(ipKey, sqlResult.getUUID().toString());
                }

                String uuidKey = "2faplus:uuid:" + sqlResult.getUUID();
                if (offset > 0) {
                    redis.sadd(uuidKey, sqlResult.getIP());
                } else {
                    redis.srem(uuidKey, sqlResult.getIP());
                }

                if (offset > 0) {
                    JSONObject obj = new JSONObject();
                    obj.put("uuid", sqlResult.getUUID().toString());
                    obj.put("ip", sqlResult.getIP());
                    obj.put("created", sqlResult.getCreated());
                    redis.publish("2faplus-login", obj.toJSONString());
                } else {
                    redis.publish("2faplus-delete", sqlResult.getUUID().toString());
                }

                return Boolean.TRUE;
            } catch (JedisException ex) {
                logger.error(ex.getMessage(), ex);
            }

            return Boolean.FALSE;
        });
    }

    public static CompletableFuture<Boolean> update(AuthyData sqlResult, JedisPool pool, ConfigurationNode redisConfigNode) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis redis = RedisUtil.getRedis(pool, redisConfigNode)) {
                if (redis == null) {
                    return Boolean.FALSE;
                }

                String key = "2faplus:authy:" + sqlResult.getUUID();
                redis.set(key, String.valueOf(sqlResult.getID()));

                JSONObject obj = new JSONObject();
                obj.put("uuid", sqlResult.getUUID().toString());
                obj.put("id", sqlResult.getID());
                redis.publish("2faplus-authy", obj.toJSONString());

                return Boolean.TRUE;
            } catch (JedisException ex) {
                logger.error(ex.getMessage(), ex);
            }

            return Boolean.FALSE;
        });
    }

    public static CompletableFuture<Boolean> delete(String ip, JedisPool pool, ConfigurationNode redisConfigNode) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis redis = RedisUtil.getRedis(pool, redisConfigNode)) {
                if (redis == null) {
                    return Boolean.FALSE;
                }

                String ipKey = "2faplus:ip:" + ip;

                Set<String> data = redis.smembers(ipKey);
                if (data != null) {
                    for (String uuid : data) {
                        String uuidKey = "2faplus:uuid:" + uuid;
                        String loginKey = "2faplus:login:" + uuid + "|" + ip;
                        String authyKey = "2faplus:authy:" + uuid;
                        redis.del(loginKey);
                        redis.del(authyKey);
                        redis.srem(uuidKey, ip);

                        redis.publish("2faplus-delete", uuid);
                    }
                }
                redis.del(ipKey);

                return Boolean.TRUE;
            } catch (JedisException ex) {
                logger.error(ex.getMessage(), ex);
            }

            return Boolean.FALSE;
        });
    }

    public static CompletableFuture<Boolean> delete(UUID uuid, JedisPool pool, ConfigurationNode redisConfigNode) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis redis = RedisUtil.getRedis(pool, redisConfigNode)) {
                if (redis == null) {
                    return Boolean.FALSE;
                }

                String uuidKey = "altfndr:uuid:" + uuid;

                Set<String> data = redis.smembers(uuidKey);
                if (data != null) {
                    for (String ip : data) {
                        String ipKey = "altfndr:ip:" + ip;
                        String loginKey = "2faplus:login:" + uuid + "|" + ip;
                        String authyKey = "2faplus:authy:" + uuid;
                        redis.del(loginKey);
                        redis.del(authyKey);
                        redis.srem(ipKey, uuid.toString());
                    }
                }
                redis.del(uuidKey);

                redis.publish("altfndr-delete", uuid.toString());

                return Boolean.TRUE;
            } catch (JedisException ex) {
                logger.error(ex.getMessage(), ex);
            }

            return Boolean.FALSE;
        });
    }

    public static CompletableFuture<Boolean> getLogin(UUID uuid, String ip, JedisPool pool, ConfigurationNode redisConfigNode) {
        return CompletableFuture.supplyAsync(() -> {
            Boolean result = null;

            try (Jedis redis = RedisUtil.getRedis(pool, redisConfigNode)) {
                if (redis != null) {
                    String key = "2faplus:login:" + uuid + "|" + ip;

                    // Grab info
                    String data = redis.get(key);
                    if (data != null) {
                        result = Boolean.valueOf(data);
                    }
                }
            } catch (JedisException ex) {
                logger.error(ex.getMessage(), ex);
            }

            return result;
        });
    }

    public static CompletableFuture<Long> getAuthy(UUID uuid, JedisPool pool, ConfigurationNode redisConfigNode) {
        return CompletableFuture.supplyAsync(() -> {
            Long result = null;

            try (Jedis redis = RedisUtil.getRedis(pool, redisConfigNode)) {
                if (redis != null) {
                    String key = "2faplus:authy:" + uuid;

                    // Grab info
                    String data = redis.get(key);
                    if (data != null) {
                        result = Long.valueOf(data);
                    }
                }
            } catch (JedisException ex) {
                logger.error(ex.getMessage(), ex);
            }

            return result;
        });
    }
}
