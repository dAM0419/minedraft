package dev.designandbuild.client.library;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * 라이브러리 항목. 서버 공용(바닐라·모드·월드)과 내 폴더(.nbt)가 같은 모양으로 보인다.
 *
 * @param id   "server:ns:path", "world:...", "file:절대경로"
 * @param tags "source:vanilla", "kind:house", "terrain:plains" ...
 */
public record LibraryEntry(String id, String name, List<String> tags, @Nullable Path file, int sizeX, int sizeY,
                           int sizeZ) {
}
