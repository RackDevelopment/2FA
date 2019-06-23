package me.egg82.tfaplus.services.lookup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import ninja.egg82.json.JSONUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

public class BukkitPlayerInfo implements PlayerInfo {
    private UUID uuid;
    private String name;

    private static Cache<UUID, String> uuidCache = Caffeine.newBuilder().expireAfterWrite(1L, TimeUnit.HOURS).build();
    private static Cache<String, UUID> nameCache = Caffeine.newBuilder().expireAfterWrite(1L, TimeUnit.HOURS).build();

    private static final Object uuidCacheLock = new Object();
    private static final Object nameCacheLock = new Object();

    public BukkitPlayerInfo(UUID uuid) throws IOException {
        this.uuid = uuid;

        Optional<String> name = Optional.ofNullable(uuidCache.getIfPresent(uuid));
        if (!name.isPresent()) {
            synchronized (uuidCacheLock) {
                name = Optional.ofNullable(uuidCache.getIfPresent(uuid));
                if (!name.isPresent()) {
                    name = Optional.of(nameExpensive(uuid));
                    uuidCache.put(uuid, name.get());
                }
            }
        }

        this.name = name.get();
    }

    public BukkitPlayerInfo(String name) throws IOException {
        this.name = name;

        Optional<UUID> uuid = Optional.ofNullable(nameCache.getIfPresent(name));
        if (!uuid.isPresent()) {
            synchronized (nameCacheLock) {
                uuid = Optional.ofNullable(nameCache.getIfPresent(name));
                if (!uuid.isPresent()) {
                    uuid = Optional.of(uuidExpensive(name));
                    nameCache.put(name, uuid.get());
                }
            }
        }

        this.uuid = uuid.get();
    }

    public UUID getUUID() { return uuid; }

    public String getName() { return name; }

    private static String nameExpensive(UUID uuid) throws IOException {
        // Currently-online lookup
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }

        // Network lookup
        HttpURLConnection conn = getConnection("https://api.mojang.com/user/profiles/" + uuid.toString().replace("-", "") + "/names");

        int code = conn.getResponseCode();
        try (InputStream in = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
             InputStreamReader reader = new InputStreamReader(in);
             BufferedReader buffer = new BufferedReader(reader)) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = buffer.readLine()) != null) {
                builder.append(line);
            }

            if (code == 200) {
                JSONArray json = JSONUtil.parseArray(builder.toString());
                JSONObject last = (JSONObject) json.get(json.size() - 1);
                String name = (String) last.get("name");

                nameCache.put(name, uuid);
            } else if (code == 204) {
                // No data exists
                return null;
            }
        } catch (ParseException ex) {
            throw new IOException(ex.getMessage(), ex);
        }

        throw new IOException("Could not load player data from Mojang (rate-limited?)");
    }

    private static UUID uuidExpensive(String name) throws IOException {
        // Currently-online lookup
        Player player = Bukkit.getPlayer(name);
        if (player != null) {
            return player.getUniqueId();
        }

        // Network lookup
        HttpURLConnection conn = getConnection("https://api.mojang.com/users/profiles/minecraft/" + name);

        int code = conn.getResponseCode();
        try (InputStream in = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
             InputStreamReader reader = new InputStreamReader(in);
             BufferedReader buffer = new BufferedReader(reader)) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = buffer.readLine()) != null) {
                builder.append(line);
            }

            if (code == 200) {
                JSONObject json = JSONUtil.parseObject(builder.toString());
                UUID uuid = UUID.fromString(((String) json.get("id")).replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"));
                name = (String) json.get("name");

                uuidCache.put(uuid, name);
            } else if (code == 204) {
                // No data exists
                return null;
            }
        } catch (ParseException ex) {
            throw new IOException(ex.getMessage(), ex);
        }

        throw new IOException("Could not load player data from Mojang (rate-limited?)");
    }

    private static HttpURLConnection getConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();

        conn.setDoInput(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("User-Agent", "egg82/BukkitPlayerInfo");
        conn.setRequestMethod("GET");

        return conn;
    }
}
