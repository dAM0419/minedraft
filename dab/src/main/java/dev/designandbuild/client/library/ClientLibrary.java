package dev.designandbuild.client.library;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.core.TemplateData;
import dev.designandbuild.core.TemplateParser;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 라이브러리: 서버 공용 목록(요청해서 받음) + 내 폴더의 .nbt (내 클라이언트에서만 보임).
 * 블록 데이터는 볼 때 하나씩 불러와 캐시한다.
 */
public final class ClientLibrary {
    private ClientLibrary() {}

    private static volatile List<LibraryEntry> serverEntries = List.of();
    private static volatile List<LibraryEntry> localEntries = List.of();
    private static boolean serverRequested;
    private static volatile boolean scanning;
    private static long revision;

    private static final Map<String, TemplateData> TEMPLATES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TemplateData> eldest) {
            return size() > 200;
        }
    };
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> MISSING = new HashSet<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DesignAndBuild library");
        t.setDaemon(true);
        return t;
    });

    public static long revision() {
        return revision;
    }

    public static boolean isScanning() {
        return scanning;
    }

    public static void reset() {
        serverEntries = List.of();
        serverRequested = false;
        synchronized (TEMPLATES) {
            TEMPLATES.clear();
        }
        PENDING.clear();
        MISSING.clear();
        revision++;
    }

    /** 편집창을 열 때: 서버 목록 요청 + 내 폴더 다시 읽기. */
    public static void refresh() {
        if (!serverRequested) {
            serverRequested = true;
            Net.toServer(Msg.C_LIBRARY_REQ, buf -> {});
        }
        rescanLocal();
    }

    public static List<LibraryEntry> all() {
        List<LibraryEntry> list = new ArrayList<>(localEntries.size() + serverEntries.size());
        list.addAll(localEntries);
        list.addAll(serverEntries);
        return list;
    }

    public static void onServerList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<LibraryEntry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf();
            String name = buf.readUtf();
            int tn = buf.readVarInt();
            List<String> tags = new ArrayList<>(tn);
            for (int k = 0; k < tn; k++) tags.add(buf.readUtf());
            list.add(new LibraryEntry(id, name, tags, null, 0, 0, 0));
        }
        serverEntries = Collections.unmodifiableList(list);
        revision++;
    }

    // ------------------------------------------------------------------
    // 내 폴더
    // ------------------------------------------------------------------

    public static void rescanLocal() {
        if (scanning) return;
        scanning = true;
        List<Path> folders = ClientConfig.allFolders();
        IO.execute(() -> {
            List<LibraryEntry> list = new ArrayList<>();
            for (Path folder : folders) {
                if (!Files.isDirectory(folder)) continue;
                try (Stream<Path> files = Files.walk(folder, 6)) {
                    for (Path f : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".nbt")).toList()) {
                        LibraryEntry e = readHeader(f);
                        if (e != null) list.add(e);
                    }
                } catch (IOException e) {
                    DesignAndBuild.LOGGER.warn("Could not scan {}", folder, e);
                }
            }
            list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            Minecraft.getInstance().execute(() -> {
                localEntries = Collections.unmodifiableList(list);
                scanning = false;
                synchronized (TEMPLATES) {
                    TEMPLATES.keySet().removeIf(k -> k.startsWith("file:"));
                }
                MISSING.removeIf(k -> k.startsWith("file:"));
                revision++;
            });
        });
    }

    /** 폴더 안 .nbt 개수 (등록 창에서 바로 보여 줌). 폴더가 아니면 -1. */
    public static int countNbt(Path folder) {
        if (!Files.isDirectory(folder)) return -1;
        try (Stream<Path> files = Files.walk(folder, 6)) {
            return (int) files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".nbt")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    @Nullable
    private static LibraryEntry readHeader(Path file) {
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(256L << 20));
            ListTag size = tag.getList("size", Tag.TAG_INT);
            int sx = size.size() == 3 ? size.getInt(0) : 0;
            int sy = size.size() == 3 ? size.getInt(1) : 0;
            int sz = size.size() == 3 ? size.getInt(2) : 0;
            String fileName = file.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - 4);
            List<String> tags = new ArrayList<>();
            tags.add("source:player");
            if (tag.contains("designandbuild", Tag.TAG_COMPOUND)) {
                CompoundTag meta = tag.getCompound("designandbuild");
                if (!meta.getString("name").isBlank()) name = meta.getString("name");
                CompoundTag t = meta.getCompound("tags");
                for (String axis : TemplateParser.TAG_AXES) {
                    ListTag l = t.getList(axis, Tag.TAG_STRING);
                    for (int i = 0; i < l.size(); i++) {
                        String full = axis + ":" + TemplateParser.clean(l.getString(i));
                        if (!tags.contains(full)) tags.add(full);
                    }
                }
            }
            return new LibraryEntry("file:" + file.toAbsolutePath().normalize(), name, tags, file, sx, sy, sz);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 블록 데이터
    // ------------------------------------------------------------------

    /** 캐시에 있으면 돌려주고, 없으면 불러오기를 시작하고 null. */
    @Nullable
    public static TemplateData template(LibraryEntry e) {
        synchronized (TEMPLATES) {
            TemplateData t = TEMPLATES.get(e.id());
            if (t != null) return t;
        }
        if (PENDING.contains(e.id()) || MISSING.contains(e.id())) return null;
        if (PENDING.size() >= 6) return null;
        PENDING.add(e.id());
        if (e.file() != null) {
            Path file = e.file();
            List<String> tags = e.tags();
            String name = e.name();
            IO.execute(() -> {
                TemplateData data = null;
                try {
                    CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(256L << 20));
                    List<String> base = new ArrayList<>();
                    base.add("source:player");
                    data = TemplateParser.parse(e.id(), name, tag, base);
                } catch (Exception ex) {
                    DesignAndBuild.LOGGER.warn("Could not read {}", file, ex);
                }
                TemplateData result = data;
                Minecraft.getInstance().execute(() -> onTemplate(e.id(), result));
            });
        } else {
            String id = e.id();
            Net.toServer(Msg.C_TEMPLATE_REQ, buf -> buf.writeUtf(id));
        }
        return null;
    }

    public static boolean isMissing(LibraryEntry e) {
        return MISSING.contains(e.id());
    }

    public static void onTemplate(String id, @Nullable TemplateData data) {
        PENDING.remove(id);
        if (data == null) {
            MISSING.add(id);
        } else {
            synchronized (TEMPLATES) {
                TEMPLATES.put(id, data);
            }
        }
        revision++;
    }

    // ------------------------------------------------------------------
    // 저장
    // ------------------------------------------------------------------

    public static Path targetFile(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (clean.isEmpty()) clean = "structure";
        return ClientConfig.defaultStructureFolder().resolve(clean + ".nbt");
    }

    /** 구조물을 .nbt 로 저장. 성공하면 저장한 경로. */
    @Nullable
    public static Path save(Path file, String name, Direction mount, Map<String, List<String>> tags, int sx, int sy, int sz,
                            int[] xs, int[] ys, int[] zs, BlockState[] states) {
        try {
            Files.createDirectories(file.getParent());
            CompoundTag tag = TemplateParser.write(name, mount, tags, sx, sy, sz, xs, ys, zs, states);
            NbtIo.writeCompressed(tag, file);
            rescanLocal();
            return file;
        } catch (IOException e) {
            DesignAndBuild.LOGGER.warn("Could not save structure {}", file, e);
            return null;
        }
    }

    /** 모든 항목에 쓰인 태그 (축별). */
    public static Map<String, List<String>> knownTags() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String axis : TemplateParser.TAG_AXES) out.put(axis, new ArrayList<>());
        for (LibraryEntry e : all()) {
            for (String t : e.tags()) {
                int c = t.indexOf(':');
                if (c < 0) continue;
                List<String> l = out.get(t.substring(0, c));
                if (l != null && !l.contains(t.substring(c + 1))) l.add(t.substring(c + 1));
            }
        }
        for (List<String> l : out.values()) Collections.sort(l);
        return out;
    }
}
