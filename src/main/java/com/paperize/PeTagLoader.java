package com.paperize;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.paperize.emc.EmcTagResolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 标签装载器：把提取的标签（c: / curios / minecraft 约定）解包到数据目录并装载进
 * {@link EmcTagResolver}，同时用服务器原版标签补齐 minecraft 命名空间。
 */
public final class PeTagLoader {

    public record Result(int written, int loadedFiles, int vanityTags, int tagCount) {
    }

    private PeTagLoader() {
    }

    public static Result load(PaperizEPlugin plugin, PeContentProvider provider, EmcTagResolver resolver)
            throws IOException {
        Path root = plugin.getDataFolder().toPath().resolve("tags");
        int written = 0;
        int loadedFiles = 0;
        List<String> vanillaIds = new ArrayList<>();

        try (InputStream indexStream = provider.openTagsIndex()) {
            if (indexStream == null) {
                return new Result(0, 0, 0, resolver.tagCount());
            }
            JsonArray index = JsonParser.parseReader(
                    new InputStreamReader(indexStream, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement element : index) {
                String rel = element.getAsString();
                try (InputStream in = provider.openTagFile(rel)) {
                    if (in == null) {
                        continue;
                    }
                    byte[] data = in.readAllBytes();
                    Path target = root.resolve(rel.replace('/', File.separatorChar));
                    if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), data)) {
                        Files.createDirectories(target.getParent());
                        Files.write(target, data);
                        written++;
                    }
                    loadedFiles++;
                    if (rel.startsWith("minecraft/")) {
                        String id = EmcTagResolver.normalize(rel);
                        if (!id.startsWith("minecraft:block/")) {
                            vanillaIds.add(id);
                        }
                    }
                }
            }
        }

        resolver.loadDirectory(root);
        int vanity = resolver.loadVanillaTags(vanillaIds);
        return new Result(written, loadedFiles, vanity, resolver.tagCount());
    }
}
