package dev.designandbuild.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.function.BiConsumer;

/**
 * 투영 비교 규칙과 재료 환산.
 *
 * 비교: 설치로 정해지는 값(방향, 축, 위·아래, 반블록 종류, 물 잠김)만 본다.
 * 주변이나 시간에 따라 바뀌는 값(연결 모양, 전원, 열림, 작물 나이 등)은 무시한다.
 *
 * 흙 계열 예외:
 * - 흙과 잔디 블록은 서로 완료로 친다 (잔디는 흙 위로 번지고, 잔디 아래는 흙이다).
 * - 흙길·경작지(와 흙)는 그 자리 흙 계열 블록을 삽·괭이로 바꿔 만든다. 부수지 않는다 ({@link #convertTool}).
 */
public final class StateRules {
    private StateRules() {}

    public static final TagKey<Block> NEEDS_SILK_TOUCH =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("designandbuild", "needs_silk_touch"));

    /** 재료 옆에 붙이는 안내 아이콘. */
    public static final int HINT_NONE = 0, HINT_SHOVEL = 1, HINT_HOE = 2, HINT_SILK = 3;

    // ------------------------------------------------------------------
    // 비교
    // ------------------------------------------------------------------

    public static boolean matches(BlockState world, BlockState target) {
        if (world == target) return true;
        if (target.isAir()) return world.isAir();
        if (soilEquivalent(world.getBlock(), target.getBlock())) return true;
        if (target.getBlock() instanceof LiquidBlock) {
            FluidState wf = world.getFluidState();
            return world.getBlock() == target.getBlock() && wf.isSource();
        }
        if (world.getBlock() != target.getBlock()) return false;
        for (Property<?> p : target.getProperties()) {
            if (isPlacementProperty(target, p) && world.hasProperty(p) && !world.getValue(p).equals(target.getValue(p))) {
                return false;
            }
        }
        return true;
    }

    /** 같은 블록이지만 방향 등 설치 값이 다르다 (주황 외곽선). */
    public static boolean sameBlockWrongState(BlockState world, BlockState target) {
        return !target.isAir() && world.getBlock() == target.getBlock() && !matches(world, target);
    }

    /** 목표를 이루려면 지금 있는 블록을 먼저 부숴야(메워야) 하는가. */
    public static boolean needsDemolish(BlockState world, BlockState target) {
        if (world.isAir()) return false;
        if (matches(world, target)) return false;
        FluidState wf = world.getFluidState();
        if (world.getBlock() instanceof LiquidBlock && !wf.isSource()) return false; // 흐르는 물은 원천을 치우면 사라진다
        if (!target.isAir()) {
            if (world.getBlock() == target.getBlock()) return false; // 방향만 다름 → 해당 단계에서 고친다
            if (convertTool(world, target) != HINT_NONE) return false; // 삽·괭이로 바꾼다
            if (world.canBeReplaced()) return false;                  // 풀·물 등은 바로 덮어 놓을 수 있다
        }
        return true;
    }

    /** 흙 ↔ 잔디 블록: 어느 쪽이 있어도 다른 쪽 목표를 이룬 것으로 본다. */
    private static boolean soilEquivalent(Block world, Block target) {
        return (target == Blocks.GRASS_BLOCK && world == Blocks.DIRT) || (target == Blocks.DIRT && world == Blocks.GRASS_BLOCK);
    }

    /**
     * 그 자리 블록을 도구로 바꿔 목표를 만들 수 있으면 그 도구 (HINT_SHOVEL / HINT_HOE), 아니면 HINT_NONE.
     * 바닐라 삽 (흙길): 잔디·흙·거친 흙·회백토·균사체·뿌리 흙. 괭이 (경작지): 잔디·흙·흙길, (흙): 거친 흙·뿌리 흙.
     */
    public static int convertTool(BlockState world, BlockState target) {
        Block w = world.getBlock(), t = target.getBlock();
        if (t == Blocks.DIRT_PATH) {
            return w == Blocks.GRASS_BLOCK || w == Blocks.DIRT || w == Blocks.COARSE_DIRT || w == Blocks.PODZOL
                    || w == Blocks.MYCELIUM || w == Blocks.ROOTED_DIRT ? HINT_SHOVEL : HINT_NONE;
        }
        if (t == Blocks.FARMLAND) {
            return w == Blocks.GRASS_BLOCK || w == Blocks.DIRT || w == Blocks.DIRT_PATH ? HINT_HOE : HINT_NONE;
        }
        if (t == Blocks.DIRT || t == Blocks.GRASS_BLOCK) {
            return w == Blocks.COARSE_DIRT || w == Blocks.ROOTED_DIRT ? HINT_HOE : HINT_NONE;
        }
        return HINT_NONE;
    }

    /** 철거 대상이 액체 원천이면 "메우기"로 표시한다. */
    public static boolean isFillTarget(BlockState world) {
        return world.getBlock() instanceof LiquidBlock && world.getFluidState().isSource();
    }

    private static boolean isPlacementProperty(BlockState state, Property<?> p) {
        return switch (p.getName()) {
            case "facing", "axis", "half", "face", "attach_face", "rotation", "orientation", "hanging", "part",
                 "waterlogged" -> true;
            case "type" -> state.getBlock() instanceof SlabBlock;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // 재료 환산
    // ------------------------------------------------------------------

    /**
     * 블록 하나를 놓는 데 드는 아이템을 out(item, count) 으로 알린다. 안내 아이콘은 hint 로 돌려준다.
     * 아이템이 없는 블록(불 등)은 Items.AIR 로 알린다.
     */
    public static int requirement(BlockState s, BiConsumer<Item, Integer> out) {
        if (s.isAir()) return HINT_NONE;
        Block block = s.getBlock();
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return HINT_NONE;
        if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return HINT_NONE;
        }
        if (block instanceof LiquidBlock) {
            FluidState f = s.getFluidState();
            if (!f.isSource()) return HINT_NONE;
            Item bucket = f.getType().getBucket();
            out.accept(bucket == Items.AIR ? Items.BUCKET : bucket, 1);
            return HINT_NONE;
        }
        int hint = s.is(NEEDS_SILK_TOUCH) ? HINT_SILK : HINT_NONE;
        Item item = block.asItem();
        int count = 1;
        if (block instanceof FarmBlock) {
            item = Items.DIRT;
            hint = HINT_HOE;
        } else if (block instanceof DirtPathBlock) {
            item = Items.DIRT;
            hint = HINT_SHOVEL;
        } else if (block == Blocks.GRASS_BLOCK) {
            item = Items.DIRT; // 흙을 놓으면 잔디가 번진다 (흙도 완료로 친다)
            hint = HINT_NONE;
        } else if (block instanceof SlabBlock && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            count = 2;
        } else if (block instanceof CandleBlock) {
            count = s.getValue(CandleBlock.CANDLES);
        } else if (block instanceof SeaPickleBlock) {
            count = s.getValue(SeaPickleBlock.PICKLES);
        } else if (block instanceof TurtleEggBlock) {
            count = s.getValue(TurtleEggBlock.EGGS);
        } else if (block instanceof SnowLayerBlock) {
            count = s.getValue(SnowLayerBlock.LAYERS);
        }
        out.accept(item, count);
        if (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED)
                && !(block instanceof LiquidBlock)) {
            out.accept(Fluids.WATER.getBucket(), 1);
        }
        return hint;
    }
}
