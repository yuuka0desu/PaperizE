#!/usr/bin/env python3
"""ProjectE jar → PE（Paper 插件）内容资源提取器。

用法：
    python tools/extract_content.py <ProjectE.jar> [输出根目录]

产物（默认写入 src/main/resources/content）：
    projecte/assets/projecte/**   原样资产（models/blockstates/textures/lang/sounds）
    recipes/**                    data/projecte/recipe 下的配方（含 conversions 子目录）
    conversions/**                pe_custom_conversions（EMC 固定值/转换）
    transmutations/**             pe_world_transmutations（哲学者之石世界转换）
    tags/<namespace>/**           物品/方块/流体标签（含 c: 与 curios 约定）
    generated/items.json          物品注册表 {name: {id, nameKey, model, ...}}
    generated/blocks.json         方块注册表 {name: {id, nameKey, model, hardness, orientation}}
    generated/resource_index.json 进入资源包的资产清单
    generated/recipes_index.json  配方清单
    generated/manifest.json       版本戳（jar sha1 + 版本）
    generated/gui-specs.json      机器 GUI 规格（纹理、槽位；勿用 screens.json 以免与 CEPlus 注册表重名）
"""
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

# ---- 进入资源包的资产命名空间前缀（其余资产仅留在插件内供引擎读取）----
PACK_PREFIXES = (
    "projecte/models/",
    "projecte/textures/",
    "projecte/lang/",
    "projecte/blockstates/",
    "projecte/sounds/",
    "projecte/sounds.json",
)

# ---- 方块属性表（硬度/抗性/朝向；数据来源：ProjectE 方块注册的 Properties 语义）----
# orientation: "facing" = 水平朝向（机器）、"any_facing" = 六向（转换台）、None = 无状态
BLOCK_PROPS = {
    "transmutation_table": {"hardness": 1.5, "resistance": 6.0, "orientation": "any_facing", "light": 0},
    "alchemical_chest": {"hardness": 3.0, "resistance": 6.0, "orientation": None, "light": 0},
    "collector_mk1": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "collector_mk2": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "collector_mk3": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "relay_mk1": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "relay_mk2": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "relay_mk3": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "condenser_mk1": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "condenser_mk2": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "dark_matter_block": {"hardness": 5.0, "resistance": 30.0, "orientation": None, "light": 0},
    "red_matter_block": {"hardness": 5.0, "resistance": 30.0, "orientation": None, "light": 0},
    "alchemical_coal_block": {"hardness": 5.0, "resistance": 6.0, "orientation": None, "light": 0},
    "mobius_fuel_block": {"hardness": 5.0, "resistance": 6.0, "orientation": None, "light": 0},
    "aeternalis_fuel_block": {"hardness": 5.0, "resistance": 6.0, "orientation": None, "light": 0},
    "dm_furnace": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "rm_furnace": {"hardness": 3.5, "resistance": 10.0, "orientation": "facing", "light": 0},
    "dm_pedestal": {"hardness": 3.5, "resistance": 10.0, "orientation": None, "light": 0},
    "interdiction_torch": {"hardness": 0.0, "resistance": 0.0, "orientation": "torch", "light": 14},
    "nova_catalyst": {"hardness": 0.0, "resistance": 0.0, "orientation": None, "light": 0, "explosive": "nova_catalyst"},
    "nova_cataclysm": {"hardness": 0.0, "resistance": 0.0, "orientation": None, "light": 0, "explosive": "nova_cataclysm"},
}

# ---- 方块名 → 方块状态文件（wall_interdiction_torch 是 interdiction_torch 的附属形态）----
STATE_ALIASES = {
    "interdiction_torch": ["interdiction_torch", "wall_interdiction_torch"],
}

# ---- GUI 规格（槽位坐标来自容器类构造函数的 ValidatedSlot/SlotGhost 定义）----
# x/y 为槽位左上角像素坐标；texture 为 textures/gui 下的文件名
SCREENS = {
    "transmutation": {
        "texture": "transmute.png",
        "size": [256, 256],
        "slots": {
            "INPUT": {"x": 43, "y": 24, "count": 1},
            "OUTPUT": {"x": 79, "y": 24, "count": 1},
            "LOCK": {"x": 61, "y": 24, "count": 1},
        },
        "player_inv": {"x": 8, "y": 84},
    },
    "alchemical_chest": {
        "texture": "alchchest.png",
        "size": [256, 256],
        "slots": {"STORAGE": {"x": 8, "y": 9, "count": 104, "grid": "13x8"}},
        "player_inv": {"x": 8, "y": 104},
    },
    "collector_mk1": {
        "texture": "collector1.png",
        "size": [256, 256],
        "slots": {
            "INPUT": {"x": 124, "y": 58, "count": 1},
            "OUTPUT": {"x": 8, "y": 18, "count": 9, "grid": "3x3"},
            "UPGRADE": {"x": 124, "y": 13, "count": 2},
            "GHOST": {"x": 153, "y": 36, "count": 1},
        },
        "player_inv": {"x": 8, "y": 84},
    },
    "collector_mk2": {
        "texture": "collector2.png",
        "size": [256, 256],
        "slots": {
            "INPUT": {"x": 124, "y": 58, "count": 1},
            "OUTPUT": {"x": 8, "y": 18, "count": 9, "grid": "3x3"},
            "UPGRADE": {"x": 124, "y": 13, "count": 2},
            "GHOST": {"x": 153, "y": 36, "count": 1},
        },
        "player_inv": {"x": 8, "y": 84},
    },
    "collector_mk3": {
        "texture": "collector3.png",
        "size": [256, 256],
        "slots": {
            "INPUT": {"x": 124, "y": 58, "count": 1},
            "OUTPUT": {"x": 8, "y": 18, "count": 9, "grid": "3x3"},
            "UPGRADE": {"x": 124, "y": 13, "count": 2},
            "GHOST": {"x": 153, "y": 36, "count": 1},
        },
        "player_inv": {"x": 8, "y": 84},
    },
    "relay_mk1": {
        "texture": "relay1.png",
        "size": [256, 256],
        "slots": {"INPUT": {"x": 8, "y": 8, "count": 1}, "OUTPUT": {"x": 8, "y": 26, "count": 1},
                  "UPGRADE": {"x": 120, "y": 8, "count": 2}},
        "player_inv": {"x": 8, "y": 84},
    },
    "relay_mk2": {
        "texture": "relay2.png",
        "size": [256, 256],
        "slots": {"INPUT": {"x": 8, "y": 8, "count": 1}, "OUTPUT": {"x": 8, "y": 26, "count": 1},
                  "UPGRADE": {"x": 120, "y": 8, "count": 2}},
        "player_inv": {"x": 8, "y": 84},
    },
    "relay_mk3": {
        "texture": "relay3.png",
        "size": [256, 256],
        "slots": {"INPUT": {"x": 8, "y": 8, "count": 1}, "OUTPUT": {"x": 8, "y": 26, "count": 1},
                  "UPGRADE": {"x": 120, "y": 8, "count": 2}},
        "player_inv": {"x": 8, "y": 84},
    },
    "condenser_mk1": {
        "texture": "condenser.png",
        "size": [256, 256],
        "slots": {"INPUT": {"x": 26, "y": 35, "count": 91, "grid": "13x7"},
                  "TARGET": {"x": 123, "y": 35, "count": 1},
                  "OUTPUT": {"x": 168, "y": 35, "count": 1},
                  "LOCK": {"x": 146, "y": 35, "count": 1}},
        "player_inv": {"x": 8, "y": 84},
    },
    "condenser_mk2": {
        "texture": "condenser_mk2.png",
        "size": [256, 256],
        "slots": {"INPUT": {"x": 26, "y": 35, "count": 91, "grid": "13x7"},
                  "TARGET": {"x": 123, "y": 35, "count": 1},
                  "OUTPUT": {"x": 168, "y": 35, "count": 1},
                  "LOCK": {"x": 146, "y": 35, "count": 1}},
        "player_inv": {"x": 8, "y": 84},
    },
    "dm_furnace": {
        "texture": "dmfurnace.png",
        "size": [256, 256],
        "slots": {"INPUT": {"x": 67, "y": 35, "count": 1}, "FUEL": {"x": 67, "y": 71, "count": 1},
                  "OUTPUT": {"x": 127, "y": 35, "count": 1}},
        "player_inv": {"x": 8, "y": 84},
    },
    "rm_furnace": {
        "texture": "rmfurnace.png",
        "size": [256, 256],
        "slots": {"INPUT": {"x": 67, "y": 35, "count": 1}, "FUEL": {"x": 67, "y": 71, "count": 1},
                  "OUTPUT": {"x": 127, "y": 35, "count": 1}},
        "player_inv": {"x": 8, "y": 84},
    },
    "manual": {"texture": "book_texture.png", "size": [256, 256], "slots": {}},
    "merc_eye": {"texture": "mercurial_eye.png", "size": [256, 256], "slots": {}},
    "eternal_density": {"texture": "eternal_density.png", "size": [256, 256], "slots": {}},
}

# ---- 箱子基类模型补全 ----
# 依据：以下 3 个方块在原模组中仅导出 particle 纹理、无 parent/elements（依赖加载器补全）；
# 其真实形态 = 箱子几何（base_chest，3 段 element：箱体/箱盖/锁扣）+ 覆盖原模组贴图。
CHEST_BASE_MODELS = {
    "alchemical_chest": "projecte:block/alchemical_chest",
    "condenser_mk1": "projecte:block/condenser_mk1",
    "condenser_mk2": "projecte:block/condenser_mk2",
}


# ---- 底材映射：工具/护甲使用原版底材（继承可用行为），其余用 paper ----
def material_for(name: str) -> str:
    if name.endswith("_pick"):
        return "netherite_pickaxe"
    if name.endswith("_axe"):
        return "netherite_axe"
    if name.endswith("_shovel"):
        return "netherite_shovel"
    if name.endswith("_hoe"):
        return "netherite_hoe"
    if name.endswith("_sword"):
        return "netherite_sword"
    if name.endswith("_shears"):
        return "shears"
    if name.endswith("_helmet"):
        return "netherite_helmet"
    if name.endswith("_chestplate"):
        return "netherite_chestplate"
    if name.endswith("_leggings"):
        return "netherite_leggings"
    if name.endswith("_boots"):
        return "netherite_boots"
    if name in ("dm_hammer", "rm_hammer", "rm_katar", "rm_morning_star"):
        return "netherite_axe"
    if name.startswith("divining_rod_"):
        return "stick"
    return "paper"


# ---- 已知纹理缺失于 assets 的物品模型映射修正（模型→纹理路径）----
MODEL_ALIASES = {
    # 未染色/状态变体：物品 id → 优先模型名（按序探测存在的模型）
    "arcana_ring": ["arcana_ring", "arcana_zero_off"],
    "black_hole_band": ["black_hole_band"],
    "divining_rod_1": ["divining_rod_1"],
    "divining_rod_2": ["divining_rod_2"],
    "divining_rod_3": ["divining_rod_3"],
    "gem_of_eternal_density": ["gem_of_eternal_density"],
    "interdiction_torch": ["interdiction_torch"],
    "low_covalence_dust": ["low_covalence_dust"],
    "medium_covalence_dust": ["medium_covalence_dust"],
    "high_covalence_dust": ["high_covalence_dust"],
}


def sha1(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


def extract(jar_path: Path, out_root: Path) -> None:
    z = zipfile.ZipFile(jar_path)
    names = z.namelist()

    content = out_root
    assets_root = content / "projecte" / "assets" / "projecte"
    recipes_root = content / "recipes"
    conversions_root = content / "conversions"
    transmutations_root = content / "transmutations"
    tags_root = content / "tags"
    generated_root = content / "generated"
    for d in (assets_root, recipes_root, conversions_root, transmutations_root, tags_root, generated_root):
        d.mkdir(parents=True, exist_ok=True)

    resource_index: list[str] = []
    existing_models: set[str] = set()
    existing_blockstates: set[str] = set()
    existing_textures: set[str] = set()

    # ---- 1. 资产 ----
    for n in names:
        if not n.startswith("assets/projecte/") or n.endswith("/"):
            continue
        rel = n[len("assets/"):]  # projecte/...
        target = content / "projecte" / "assets" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(n))
        if rel.startswith(PACK_PREFIXES):
            resource_index.append(rel)
        m = re.match(r"projecte/models/item/(.+)\.json$", rel)
        if m:
            existing_models.add(m.group(1))
        m = re.match(r"projecte/models/block/(.+)\.json$", rel)
        if m:
            existing_models.add("block/" + m.group(1))
        m = re.match(r"projecte/blockstates/(.+)\.json$", rel)
        if m:
            existing_blockstates.add(m.group(1))
        m = re.match(r"projecte/textures/(.+)\.png$", rel)
        if m:
            existing_textures.add(m.group(1))

    # ---- 2. 配方 ----
    recipe_index: list[str] = []
    for n in names:
        if not n.startswith("data/projecte/recipe/") or n.endswith("/"):
            continue
        rel = n[len("data/projecte/recipe/"):]
        target = recipes_root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(n))
        recipe_index.append(rel)

    # ---- 2b. 转换 / 世界转换索引 ----
    conversion_index: list[str] = []
    for n in names:
        if n.startswith("data/projecte/pe_custom_conversions/") and not n.endswith("/"):
            conversion_index.append(n[len("data/projecte/pe_custom_conversions/"):])
    transmutation_index: list[str] = []
    for n in names:
        if n.startswith("data/projecte/pe_world_transmutations/") and not n.endswith("/"):
            transmutation_index.append(n[len("data/projecte/pe_world_transmutations/"):])

    # ---- 3. 自定义转换 ----
    for n in names:
        if not n.startswith("data/projecte/pe_custom_conversions/") or n.endswith("/"):
            continue
        rel = n[len("data/projecte/pe_custom_conversions/"):]
        target = conversions_root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(n))

    # ---- 4. 世界转换 ----
    for n in names:
        if not n.startswith("data/projecte/pe_world_transmutations/") or n.endswith("/"):
            continue
        rel = n[len("data/projecte/pe_world_transmutations/"):]
        target = transmutations_root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(n))

    # ---- 5. 标签（含 c: 与 curios 约定，供转换器做材质闭包校验）----
    tag_index: list[str] = []
    for prefix, ns in (("data/c/", "c"), ("data/curios/", "curios"), ("data/minecraft/", "minecraft")):
        for n in names:
            if not n.startswith(prefix) or n.endswith("/"):
                continue
            rel = n[len(prefix):]  # 保留 "tags/" 段：供 EmcTagResolver.normalize 解析
            if not rel.startswith("tags/"):
                continue
            out_rel = ns + "/" + rel
            target = tags_root / out_rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(n))
            if out_rel.endswith(".json"):
                tag_index.append(out_rel)

    # ---- 5b. 箱子基类模型补全（凝聚器/炼金箱）----
    patched_models = 0
    for name, texture in CHEST_BASE_MODELS.items():
        model_file = assets_root / "models" / "block" / f"{name}.json"
        if not model_file.is_file():
            continue
        current = json.loads(model_file.read_text(encoding="utf-8"))
        if current.get("elements") or current.get("parent"):
            continue  # 已完整（上游更新时尊重原文件）
        patched = {
            "parent": "projecte:block/base_chest",
            "textures": {
                "chest": texture,
                "particle": texture,
            },
        }
        model_file.write_text(json.dumps(patched, ensure_ascii=False), encoding="utf-8")
        patched_models += 1
    if patched_models:
        print(f"模型补全：{patched_models} 个箱子基类模型（凝聚器/炼金箱）已重建 parent+贴图覆盖")

    # ---- 5c. minecraft 命名空间资产（容器皮肤替换）追加进资源包索引 ----
    mc_root = content / "projecte" / "assets" / "minecraft"
    mc_count = 0
    if mc_root.is_dir():
        for path in sorted(mc_root.rglob("*")):
            if path.is_file():
                rel = "minecraft/" + path.relative_to(mc_root).as_posix()
                if rel not in resource_index:
                    resource_index.append(rel)
                    mc_count += 1
    if mc_count:
        print(f"容器皮肤资产入包：{mc_count} 个（minecraft 命名空间）")

    # ---- 6. 注册表生成 ----
    lang_en = json.loads(z.read("assets/projecte/lang/en_us.json").decode("utf-8"))
    lang_zh = None
    try:
        lang_zh = json.loads(z.read("assets/projecte/lang/zh_cn.json").decode("utf-8"))
    except KeyError:
        pass

    # 物品：lang 键 ∩（模型或纹理存在）
    items = {}
    model_dupes = {}
    for key in lang_en:
        if not key.startswith("item.projecte."):
            continue
        name = key[len("item.projecte."):]
        candidates = MODEL_ALIASES.get(name, [name])
        model = None
        for cand in candidates:
            if cand in existing_models:
                model = "projecte:item/" + cand
                break
        if model is None and name in existing_models:
            model = "projecte:item/" + name
        if model is None:
            continue
        entry = {
            "id": "projecte:" + name,
            "nameKey": key,
            "model": model,
            "material": material_for(name),
        }
        if lang_zh and key in lang_zh:
            entry["zhName"] = lang_zh[key]
        items[name] = entry
        model_dupes.setdefault(model, []).append(name)

    # 方块
    blocks = {}
    for key in lang_en:
        if not key.startswith("block.projecte."):
            continue
        name = key[len("block.projecte."):]
        states = STATE_ALIASES.get(name, [name])
        present = [s for s in states if s in existing_blockstates]
        if not present:
            continue
        model = "projecte:block/" + name
        if ("block/" + name) not in existing_models:
            # 从 blockstates 解析归属模型
            bs = json.loads(z.read(f"assets/projecte/blockstates/{present[0]}.json").decode("utf-8"))
            variants = bs.get("variants", {})
            first = next(iter(variants.values()))
            first = first[0] if isinstance(first, list) else first
            model = first.get("model", model)
        props = BLOCK_PROPS.get(name, {"hardness": 2.0, "resistance": 6.0, "orientation": None})
        entry = {
            "id": "projecte:" + name,
            "nameKey": key,
            "model": model,
            "blockstates": present,
            "hardness": props["hardness"],
            "resistance": props["resistance"],
            "orientation": props.get("orientation"),
            "light": props.get("light", 0),
        }
        if "explosive" in props:
            entry["explosive"] = props["explosive"]
        if lang_zh and key in lang_zh:
            entry["zhName"] = lang_zh[key]
        blocks[name] = entry

    (generated_root / "items.json").write_text(
        json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")
    (generated_root / "blocks.json").write_text(
        json.dumps(blocks, ensure_ascii=False, indent=1), encoding="utf-8")
    (generated_root / "resource_index.json").write_text(
        json.dumps(sorted(resource_index), ensure_ascii=False, indent=1), encoding="utf-8")
    (generated_root / "recipes_index.json").write_text(
        json.dumps(sorted(recipe_index), ensure_ascii=False, indent=1), encoding="utf-8")
    (generated_root / "conversions_index.json").write_text(
        json.dumps(sorted(conversion_index), ensure_ascii=False, indent=1), encoding="utf-8")
    (generated_root / "transmutations_index.json").write_text(
        json.dumps(sorted(transmutation_index), ensure_ascii=False, indent=1), encoding="utf-8")
    (generated_root / "tags_index.json").write_text(
        json.dumps(sorted(tag_index), ensure_ascii=False, indent=1), encoding="utf-8")
    (generated_root / "gui-specs.json").write_text(
        json.dumps(SCREENS, ensure_ascii=False, indent=1), encoding="utf-8")

    # 资产内容哈希：内容变化（含模型补全）必须改变版本戳，否则引擎会跳过 overlay 重写
    content_hash = hashlib.sha1()
    for rel in sorted(resource_index):
        path = content / "projecte" / "assets" / rel
        try:
            content_hash.update(rel.encode("utf-8"))
            content_hash.update(sha1(path.read_bytes()).encode("utf-8"))
        except OSError:
            pass

    jar_bytes = jar_path.read_bytes()
    manifest = {
        "jarSha1": sha1(jar_bytes),
        "contentHash": content_hash.hexdigest(),
        "jarName": jar_path.name,
        "namespace": "projecte",
        "itemCount": len(items),
        "blockCount": len(blocks),
        "recipeCount": len(recipe_index),
    }
    (generated_root / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"资产文件: {len(resource_index)}（进入资源包）")
    print(f"物品: {len(items)}  方块: {len(blocks)}  配方: {len(recipe_index)}")
    print(f"生成注册表 → {generated_root}")
    missing = sorted(k[len('item.projecte.'):] for k in lang_en
                     if k.startswith("item.projecte.") and k[len("item.projecte."):] not in items)
    if missing:
        print(f"无模型物品（{len(missing)}）: {', '.join(missing)}")


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    jar = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parent.parent / "src/main/resources/content"
    if not jar.is_file():
        print(f"jar 不存在: {jar}")
        sys.exit(1)
    extract(jar, out)


if __name__ == "__main__":
    main()
