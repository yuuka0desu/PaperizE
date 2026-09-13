package com.paperize;

import com.ceplus.api.content.ContentProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ProjectE 内容提供者：向 CEPlus 引擎提供打包资产与内容注册表。
 *
 * <p>数据源：
 * <ul>
 *   <li>jar 内 {@code content/projecte/assets/**}（ProjectE 原始资产）与
 *       {@code content/generated/*.json}（注册表）</li>
 *   <li>数据目录 {@code generated-assets/assets/**}（转换器生成的补充模型）</li>
 * </ul>
 */
public final class PeContentProvider implements ContentProvider {

    public static final String ID = "projecte";
    public static final String NAMESPACE = "projecte";

    /** 进入资源包的资产前缀（其余资产只留在插件内供引擎读取）。 */
    private static final List<String> PACK_PREFIXES = List.of(
            "projecte/models/",
            "projecte/textures/",
            "projecte/lang/",
            "projecte/blockstates/",
            "projecte/sounds/",
            "projecte/sounds.json",
            // 容器皮肤（箱子界面底图替换）
            "minecraft/textures/");

    private final PaperizEPlugin plugin;
    private final Path generatedAssetsDir;
    private final List<String> jarAssets;
    private final String jarStamp;

    public PeContentProvider(PaperizEPlugin plugin) {
        this.plugin = plugin;
        this.generatedAssetsDir = plugin.getDataFolder().toPath().resolve("generated-assets/assets");
        this.jarAssets = readIndex();
        this.jarStamp = readStamp();
    }

    /** 转换器生成的补充资产目录（assets 根形态）。 */
    public Path generatedAssetsDir() {
        return generatedAssetsDir;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    @Override
    public String versionStamp() {
        StringBuilder sb = new StringBuilder(jarStamp);
        if (Files.isDirectory(generatedAssetsDir)) {
            try (var stream = Files.walk(generatedAssetsDir)) {
                stream.filter(Files::isRegularFile).sorted().forEach(p -> {
                    try {
                        sb.append(':').append(generatedAssetsDir.relativize(p)).append('=').append(Files.size(p));
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    @Override
    public List<String> packAssets() {
        List<String> out = new ArrayList<>(jarAssets);
        if (Files.isDirectory(generatedAssetsDir)) {
            try (var stream = Files.walk(generatedAssetsDir)) {
                stream.filter(Files::isRegularFile)
                        .map(p -> generatedAssetsDir.relativize(p).toString().replace(File.separatorChar, '/'))
                        .sorted()
                        .forEach(out::add);
            } catch (IOException e) {
                plugin.getLogger().warning("扫描生成资产目录失败: " + e.getMessage());
            }
        }
        return out;
    }

    @Override
    public InputStream openPackAsset(String relativePath) {
        Path generated = generatedAssetsDir.resolve(relativePath.replace('/', File.separatorChar));
        if (Files.isRegularFile(generated)) {
            try {
                return Files.newInputStream(generated);
            } catch (IOException e) {
                plugin.getLogger().warning("读取生成资产失败: " + relativePath + "（" + e.getMessage() + "）");
            }
        }
        return getClass().getClassLoader().getResourceAsStream("content/projecte/assets/" + relativePath);
    }

    @Override
    public InputStream openRegistry(String registryName) {
        return getClass().getClassLoader().getResourceAsStream("content/generated/" + registryName + ".json");
    }

    /** 原始物品定义（content/generated 之外的补充字段用）。 */
    public InputStream openItemDefinition(String name) {
        return getClass().getClassLoader().getResourceAsStream("content/projecte/assets/projecte/models/item/" + name + ".json");
    }

    /** 单个方块状态文件。 */
    public InputStream openBlockstate(String name) {
        return getClass().getClassLoader().getResourceAsStream("content/projecte/assets/projecte/blockstates/" + name + ".json");
    }

    /** 标签索引（content/tags 下的相对路径清单）。 */
    public InputStream openTagsIndex() {
        return getClass().getClassLoader().getResourceAsStream("content/generated/tags_index.json");
    }

    /** 单个标签文件（相对 content/tags）。 */
    public InputStream openTagFile(String relativePath) {
        return getClass().getClassLoader().getResourceAsStream("content/tags/" + relativePath);
    }

    /** 配方索引（content/recipes 下的相对路径清单）。 */
    public InputStream openRecipesIndex() {
        return getClass().getClassLoader().getResourceAsStream("content/generated/recipes_index.json");
    }

    /** 单个配方文件（相对 content/recipes）。 */
    public InputStream openRecipe(String relativePath) {
        return getClass().getClassLoader().getResourceAsStream("content/recipes/" + relativePath);
    }

    /** 单个自定义转换文件（相对 content/conversions）。 */
    public InputStream openConversion(String relativePath) {
        return getClass().getClassLoader().getResourceAsStream("content/conversions/" + relativePath);
    }

    /** 单个世界转换文件（相对 content/transmutations）。 */
    public InputStream openTransmutation(String relativePath) {
        return getClass().getClassLoader().getResourceAsStream("content/transmutations/" + relativePath);
    }

    private List<String> readIndex() {
        try (InputStream in = open("content/generated/resource_index.json")) {
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            List<String> out = new ArrayList<>();
            for (var element : arr) {
                String rel = element.getAsString();
                if (PACK_PREFIXES.stream().anyMatch(rel::startsWith)) {
                    out.add(rel);
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("读取资源索引失败（请先运行 tools/extract_content.py）", e);
        }
    }

    private String readStamp() {
        try (InputStream in = open("content/generated/manifest.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            // 内容哈希优先：资产内容变化（含模型/碰撞修复）必须改变版本戳，
            // 否则引擎按旧戳跳过 overlay 重写，修复不会生效。
            if (o.has("contentHash") && o.get("contentHash").isJsonPrimitive()) {
                return o.get("contentHash").getAsString();
            }
            return o.has("jarSha1") ? o.get("jarSha1").getAsString() : "1";
        } catch (Exception e) {
            plugin.getLogger().warning("读取内容版本戳失败，回退为固定值: " + e.getMessage());
            return "1";
        }
    }

    private InputStream open(String resource) {
        InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("缺少内置资源: " + resource);
        }
        return in;
    }
}
