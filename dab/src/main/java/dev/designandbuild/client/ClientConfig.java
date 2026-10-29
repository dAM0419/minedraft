package dev.designandbuild.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.designandbuild.DesignAndBuild;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 클라이언트 설정 (config/designandbuild-client.json). 플레이어별로 남는 것만 둔다. */
public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data = new Data();

    public static final class Data {
        /** 구조물 .nbt 를 찾을 폴더 (기본 폴더는 항상 포함). */
        public List<String> folders = new ArrayList<>();
        /** 팔레트 20칸 (아이템 ID, 빈 칸은 ""). */
        public List<String> palette = new ArrayList<>();
        public List<String> recent = new ArrayList<>();
        public List<String> favorites = new ArrayList<>();
        /** 블록 선택기에서 최근에 고른 블록. */
        public List<String> recentBlocks = new ArrayList<>();
        /** 이 블록 수를 넘는 조작은 한 번 더 눌러야 적용된다. */
        public int largeOpThreshold = 20000;
        /** 커서 버블 반지름 (GUI 픽셀). */
        public int bubbleRadius = 56;
        /** 투영 HUD 위치 조정 (다른 HUD 모드와 겹칠 때). */
        public int progressOffsetX = 0, progressOffsetY = 0;
        public int materialsOffsetX = 0, materialsOffsetY = 0;
        /** 도면별: 층 표시 "이 층 이하"인가. */
        public Map<String, Boolean> layerBelow = new HashMap<>();
        /** 도면별: 층 표시 "단계 전체"(모든 층)인가. 있으면 layerBelow 보다 우선. */
        public Set<String> layerAll = new HashSet<>();
        /** 라이브러리 숨김 목록 (한 줄 = tag:축:값 / structure:ID, client.library.HiddenList). */
        public Set<String> hidden = new java.util.LinkedHashSet<>();
        /** 도면별: 투영을 끈 도면. */
        public Set<String> projectionOff = new HashSet<>();
        public boolean paletteCollapsed = false;
        public boolean panelCollapsed = false;
        /** 편집창: 블록 테두리 선 (G). */
        public boolean blockOutlines = true;
        /** 투영 블록 불투명도 (%, 30~80). 투영 전체를 한 장으로 그린 뒤 이 값으로 덮는다. */
        public int projectionOpacity = 55;
    }

    private ClientConfig() {}

    public static Data get() {
        return data;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("designandbuild-client.json");
    }

    public static Path defaultStructureFolder() {
        return FMLPaths.GAMEDIR.get().resolve("designandbuild").resolve("structures");
    }

    public static void load() {
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                Data d = GSON.fromJson(r, Data.class);
                if (d != null) data = d;
            } catch (Exception e) {
                DesignAndBuild.LOGGER.warn("Could not read {}", f, e);
            }
        }
        if (data.folders == null) data.folders = new ArrayList<>();
        if (data.palette == null) data.palette = new ArrayList<>();
        if (data.recent == null) data.recent = new ArrayList<>();
        if (data.favorites == null) data.favorites = new ArrayList<>();
        if (data.recentBlocks == null) data.recentBlocks = new ArrayList<>();
        if (data.layerBelow == null) data.layerBelow = new HashMap<>();
        if (data.layerAll == null) data.layerAll = new HashSet<>();
        if (data.hidden == null) data.hidden = new java.util.LinkedHashSet<>();
        if (data.projectionOff == null) data.projectionOff = new HashSet<>();
        while (data.palette.size() < 20) data.palette.add("");
        try {
            Files.createDirectories(defaultStructureFolder());
        } catch (IOException ignored) {
        }
    }

    public static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
        } catch (IOException e) {
            DesignAndBuild.LOGGER.warn("Could not write {}", f, e);
        }
    }

    /** 기본 폴더 + 등록 폴더. */
    public static List<Path> allFolders() {
        List<Path> list = new ArrayList<>();
        list.add(defaultStructureFolder().toAbsolutePath().normalize());
        for (String s : data.folders) {
            try {
                Path p = Path.of(s).toAbsolutePath().normalize();
                if (!list.contains(p)) list.add(p);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    public static void touchRecent(String id) {
        data.recent.remove(id);
        data.recent.add(0, id);
        while (data.recent.size() > 12) data.recent.remove(data.recent.size() - 1);
        save();
    }
}
