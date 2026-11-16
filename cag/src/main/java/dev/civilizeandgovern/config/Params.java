package dev.civilizeandgovern.config;

import dev.civilizeandgovern.CaG;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 모든 수치를 읽는 한 곳 (명세 08-params.md, 불변식 8).
 * 기본값은 jar 안의 cag_params.properties (명세의 표에서 뽑아 만든 파일),
 * config/civilizeandgovern-params.properties 가 있으면 그 값이 덮어쓴다.
 */
public final class Params {
    private Params() {}

    private static final Map<String, Double> VALUES = new HashMap<>();

    public static void load() {
        VALUES.clear();
        try (InputStream in = Params.class.getResourceAsStream("/cag_params.properties")) {
            if (in == null) throw new IllegalStateException("cag_params.properties missing from jar");
            read(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), true);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        Path override = FMLPaths.CONFIGDIR.get().resolve("civilizeandgovern-params.properties");
        if (Files.isRegularFile(override)) {
            try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                read(r, false);
            } catch (java.io.IOException e) {
                CaG.LOGGER.warn("CaG: could not read {}", override, e);
            }
        }
    }

    private static void read(Reader reader, boolean defaults) throws java.io.IOException {
        Properties p = new Properties();
        p.load(reader);
        for (String key : p.stringPropertyNames()) {
            if (!defaults && !VALUES.containsKey(key)) {
                CaG.LOGGER.warn("CaG: unknown parameter '{}' in config, ignored", key);
                continue;
            }
            try {
                VALUES.put(key, Double.parseDouble(p.getProperty(key).trim()));
            } catch (NumberFormatException e) {
                CaG.LOGGER.warn("CaG: parameter '{}' is not a number: {}", key, p.getProperty(key));
            }
        }
    }

    /** 실수 값. 없는 키는 프로그램 오류다. */
    public static double d(String key) {
        Double v = VALUES.get(key);
        if (v == null) throw new IllegalArgumentException("Unknown CaG parameter " + key);
        return v;
    }

    public static int i(String key) {
        return (int) Math.round(d(key));
    }
}
