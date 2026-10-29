package dev.designandbuild.server;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.core.TemplateData;
import dev.designandbuild.core.TemplateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 서버 공용 구조물 목록: 바닐라·모드의 data/&lt;ns&gt;/structure/**.nbt 와 월드의 generated 구조물.
 * 목록은 이름·태그만 보내고, 블록 데이터는 클라이언트가 볼 때 하나씩 요청한다.
 */
public final class StructureLibrary {
    private StructureLibrary() {}

    public record Entry(String id, String name, List<String> tags) {}

    private static volatile List<Entry> entries = List.of();
    private static volatile boolean dirty = true;
    private static final Map<String, TemplateData> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TemplateData> eldest) {
            return size() > 160;
        }
    };

    public static void markDirty() {
        dirty = true;
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    public static List<Entry> entries(MinecraftServer server) {
        if (dirty) {
            dirty = false;
            entries = scan(server);
        }
        return entries;
    }

    public static void writeList(FriendlyByteBuf buf, List<Entry> list) {
        buf.writeVarInt(list.size());
        for (Entry e : list) {
            buf.writeUtf(e.id());
            buf.writeUtf(e.name());
            buf.writeVarInt(e.tags().size());
            for (String t : e.tags()) buf.writeUtf(t);
        }
    }

    private static List<Entry> scan(MinecraftServer server) {
        List<Entry> list = new ArrayList<>();
        ResourceManager rm = server.getResourceManager();
        Map<ResourceLocation, Resource> found = rm.listResources("structure", rl -> rl.getPath().endsWith(".nbt"));
        for (ResourceLocation rl : found.keySet()) {
            String path = rl.getPath().substring("structure/".length(), rl.getPath().length() - 4);
            if (path.contains("villagers/") || path.contains("/animals") || path.startsWith("animals/")) continue;
            list.add(new Entry("server:" + rl.getNamespace() + ":" + path, TemplateParser.prettyName(path),
                    TemplateParser.autoTags(rl.getNamespace(), path)));
        }
        // 구조물 블록으로 저장한 월드 구조물
        Path generated = server.getWorldPath(LevelResource.GENERATED_DIR);
        if (Files.isDirectory(generated)) {
            try (Stream<Path> namespaces = Files.list(generated)) {
                for (Path ns : namespaces.toList()) {
                    for (String dir : new String[]{"structure", "structures"}) {
                        Path root = ns.resolve(dir);
                        if (!Files.isDirectory(root)) continue;
                        try (Stream<Path> files = Files.walk(root, 6)) {
                            for (Path f : files.filter(p -> p.toString().endsWith(".nbt")).toList()) {
                                String rel = root.relativize(f).toString().replace('\\', '/');
                                rel = rel.substring(0, rel.length() - 4);
                                List<String> tags = new ArrayList<>();
                                tags.add("source:world");
                                list.add(new Entry("world:" + ns.getFileName() + ":" + dir + ":" + rel,
                                        TemplateParser.prettyName(rel), tags));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                DesignAndBuild.LOGGER.warn("Could not list generated structures", e);
            }
        }
        list.sort(Comparator.comparing(Entry::id));
        return List.copyOf(list);
    }

    /** 구조물 데이터. 없거나 읽을 수 없으면 null. */
    @Nullable
    public static TemplateData load(MinecraftServer server, String id) {
        synchronized (CACHE) {
            TemplateData cached = CACHE.get(id);
            if (cached != null) return cached;
        }
        TemplateData data = null;
        try {
            if (id.startsWith("server:")) {
                String rest = id.substring("server:".length());
                int colon = rest.indexOf(':');
                if (colon < 0) return null;
                String ns = rest.substring(0, colon);
                String path = rest.substring(colon + 1);
                ResourceLocation file = ResourceLocation.fromNamespaceAndPath(ns, "structure/" + path + ".nbt");
                Resource res = server.getResourceManager().getResource(file).orElse(null);
                if (res == null) return null;
                try (InputStream in = res.open()) {
                    CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.create(256L << 20));
                    data = TemplateParser.parse(id, TemplateParser.prettyName(path), tag, TemplateParser.autoTags(ns, path));
                }
            } else if (id.startsWith("world:")) {
                String[] parts = id.split(":", 4);
                if (parts.length < 4) return null;
                Path root = server.getWorldPath(LevelResource.GENERATED_DIR).resolve(parts[1]).resolve(parts[2]).normalize();
                Path file = root.resolve(parts[3] + ".nbt").normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) return null;
                try (InputStream in = Files.newInputStream(file)) {
                    CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.create(256L << 20));
                    List<String> tags = new ArrayList<>();
                    tags.add("source:world");
                    data = TemplateParser.parse(id, TemplateParser.prettyName(parts[3]), tag, tags);
                }
            }
        } catch (Exception e) {
            DesignAndBuild.LOGGER.warn("Could not read structure {}", id, e);
            return null;
        }
        if (data != null) {
            synchronized (CACHE) {
                CACHE.put(id, data);
            }
        }
        return data;
    }
}
