package dev.minedraft.lib.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.minedraft.lib.MinedraftLib;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 편집창 공용 설정 (config/minedraftlib-client.json). 플레이어별로 남는 것만 둔다. */
public final class LibConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data = new Data();
    private static boolean loaded;

    public static final class Data {
        /** 편집창: 블록 테두리 선 (G). */
        public boolean blockOutlines = true;
        /** 커서 버블 반지름 (GUI 픽셀). */
        public int bubbleRadius = 56;
        /** 우측 패널 접힘 (Tab). */
        public boolean panelCollapsed = false;
        /** 블록 선택기에서 최근에 고른 블록. */
        public List<String> recentBlocks = new ArrayList<>();
    }

    private LibConfig() {}

    public static Data get() {
        if (!loaded) load();
        return data;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("minedraftlib-client.json");
    }

    public static void load() {
        loaded = true;
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                Data d = GSON.fromJson(r, Data.class);
                if (d != null) data = d;
            } catch (Exception e) {
                MinedraftLib.LOGGER.warn("Could not read {}", f, e);
            }
        }
        if (data.recentBlocks == null) data.recentBlocks = new ArrayList<>();
    }

    public static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
        } catch (IOException e) {
            MinedraftLib.LOGGER.warn("Could not write {}", f, e);
        }
    }
}
