package dev.designandbuild.client.projection;

import dev.designandbuild.Registration;
import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.ClientStore;
import dev.designandbuild.client.Keys;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.item.BlueprintRef;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 도면을 손에 들었을 때의 투영. 주손 도면을 먼저 보고, 없으면 보조손 도면.
 * 들면 서버에 구독을 걸어 설계 변경을 받고, 놓으면 구독을 푼다.
 */
public final class ProjectionManager {
    private ProjectionManager() {}

    @Nullable
    private static UUID subscribed;
    @Nullable
    private static Projection active;
    private static final Map<UUID, Projection> CACHE = new HashMap<>();
    private static int tick;
    private static int rebuildCooldown;
    private static int materialsAge = 999;

    /** 재료 목록 표시: 0 = 접힘, 1 = 펼침(현재 층), 2 = 펼침(현재 단계 전체). */
    public static int materialsMode;
    static List<Projection.Material> layerMaterials = List.of();
    static List<Projection.Material> stageMaterials = List.of();

    @Nullable
    public static Projection active() {
        return active;
    }

    public static List<Projection.Material> layerMaterials() {
        return layerMaterials;
    }

    public static List<Projection.Material> stageMaterials() {
        return stageMaterials;
    }

    /** 건설망치가 지금 겨누는 투영 칸 (없으면 null). 틱마다 다시 구한다. */
    @Nullable
    public static net.minecraft.core.BlockPos hammerTarget;
    /** 서버에 마지막으로 보낸 보기 상태 (같으면 다시 안 보냄). */
    @Nullable
    private static String sentView;

    /** 도면별 즉시 반영 상태 (서버가 알려 줌). */
    public record InstantState(boolean allowed, boolean running, boolean hasUndo, int undoKind, int undoStructure) {}

    public static final Map<UUID, InstantState> INSTANT = new HashMap<>();

    public static void reset() {
        hammerTarget = null;
        sentView = null;
        INSTANT.clear();
        subscribed = null;
        active = null;
        for (Projection p : CACHE.values()) p.detach();
        CACHE.clear();
        ProjectionRenderer.clear();
        layerMaterials = List.of();
        stageMaterials = List.of();
    }

    public static void onModelChanged(BlueprintModel m) {
        // 버전·리비전으로 다음 틱에 바뀐 칸만 고친다. 재료 목록은 정해진 주기로 다시 센다
    }

    public static void onModelReplaced(BlueprintModel m) {
        Projection p = CACHE.get(m.id);
        if (p != null) p.replaceModel(m);
        materialsAge = 999;
    }

    /** 도면 메뉴에서 층 보기 방식을 바꿨을 때. */
    public static void markAllDirty() {
        if (active != null) {
            active.refreshViewMode();
            active.allDirty = true;
        }
    }

    @Nullable
    private static BlueprintRef heldRef(LocalPlayer player, ClientLevel level) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(Registration.BLUEPRINT.get())) continue;
            BlueprintRef ref = stack.get(Registration.BLUEPRINT_REF.get());
            if (ref == null) continue;
            if (!ref.dimension().equals(level.dimension().location().toString())) continue;
            return ref;
        }
        return null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        tick++;
        if (player == null || level == null) {
            if (subscribed != null || active != null) reset();
            drainKeys();
            return;
        }
        BlueprintRef ref = heldRef(player, level);
        UUID held = ref == null || ClientConfig.get().projectionOff.contains(ref.id().toString()) ? null : ref.id();
        if (!Objects.equals(held, subscribed)) {
            if (subscribed != null) {
                UUID old = subscribed;
                Net.toServer(Msg.C_UNSUBSCRIBE, b -> b.writeUUID(old));
            }
            subscribed = held;
            active = null;
            ProjectionRenderer.clear();
            if (held != null) {
                BlueprintModel m = ClientStore.MODELS.get(held);
                long known = m == null ? -1 : m.version;
                Net.toServer(Msg.C_SUBSCRIBE, b -> {
                    b.writeUUID(held);
                    b.writeVarLong(known);
                });
            }
        }
        if (subscribed == null) {
            hammerTarget = null;
            sendView(null);
            drainKeys();
            return;
        }
        if (active == null) {
            BlueprintModel m = ClientStore.MODELS.get(subscribed);
            if (m == null) {
                drainKeys();
                return;
            }
            active = CACHE.computeIfAbsent(subscribed, id -> new Projection(m));
            if (active.model != m) active.replaceModel(m);
            materialsAge = 999;
        }
        Projection p = active;
        if (rebuildCooldown > 0) rebuildCooldown--;
        if (p.needsRebuild() && rebuildCooldown <= 0) {
            p.rebuild(level);
            rebuildCooldown = 10;
            materialsAge = 999;
        }
        // 전체 칸은 약 1초에 한 바퀴 다시 본다 (주변 칸은 stepNear 가 더 자주)
        p.step(level, p.isReady() ? Math.max(512, Math.min(25_000, (p.n + 19) / 20)) : 200_000);
        if (tick % 4 == 0) p.stepNear(level, player.blockPosition(), 1);
        int stageBefore = p.currentStage();
        int layerBefore = p.currentLayer();
        p.updateNavigation();
        handleKeys(p, player);
        if (p.currentStage() != stageBefore || p.currentLayer() != layerBefore) materialsAge = 999;
        announce(p, mc);
        sendView(p);
        hammerTarget = player.getMainHandItem().is(Registration.HAMMER.get())
                ? p.pickPlaceable(level, player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange())
                : null;
        ProjectionRenderer.update(p);
        if (++materialsAge >= 10) {
            materialsAge = 0;
            // 지금 보이는 목록 하나만 센다 (펼침 방식을 바꾸면 바로 다시 센다)
            if (materialsMode == 2) {
                stageMaterials = p.materials(false);
            } else {
                layerMaterials = p.materials(true);
            }
        }
    }

    /** 보는 단계·층이 바뀌면 서버에 알린다 (건설망치 판정용). */
    private static void sendView(@Nullable Projection p) {
        String key;
        if (p == null || !p.isReady()) {
            key = "-";
        } else {
            int[] k = p.currentStageKey();
            key = p.model.id + "|" + k[0] + "|" + k[1] + "|" + p.layerMode() + "|" + p.currentLayer();
        }
        if (key.equals(sentView)) return;
        if (sentView == null && key.equals("-")) {
            sentView = key;
            return;
        }
        sentView = key;
        if (p == null || !p.isReady()) {
            Net.toServer(Msg.C_VIEW_STATE, b -> b.writeBoolean(false));
            return;
        }
        int[] k = p.currentStageKey();
        UUID id = p.model.id;
        int mode = p.layerMode();
        int y = p.currentLayer();
        boolean topDown = p.currentStage() == Projection.STAGE_DEMOLISH;
        Net.toServer(Msg.C_VIEW_STATE, b -> {
            b.writeBoolean(true);
            b.writeUUID(id);
            b.writeVarInt(k[0]);
            b.writeVarInt(k[1]);
            b.writeByte(mode);
            b.writeInt(y);
            b.writeBoolean(topDown);
        });
    }

    private static void drainKeys() {
        while (Keys.LAYER_UP.consumeClick()) ;
        while (Keys.LAYER_DOWN.consumeClick()) ;
        while (Keys.STAGE_PREV.consumeClick()) ;
        while (Keys.STAGE_NEXT.consumeClick()) ;
        while (Keys.MATERIALS.consumeClick()) ;
    }

    private static void handleKeys(Projection p, LocalPlayer player) {
        while (Keys.LAYER_UP.consumeClick()) p.moveLayer(1, player.getBlockY());
        while (Keys.LAYER_DOWN.consumeClick()) p.moveLayer(-1, player.getBlockY());
        while (Keys.STAGE_PREV.consumeClick()) p.moveStage(-1);
        while (Keys.STAGE_NEXT.consumeClick()) p.moveStage(1);
        while (Keys.MATERIALS.consumeClick()) {
            materialsMode = (materialsMode + 1) % 3;
            materialsAge = 999;
        }
    }

    private static void announce(Projection p, Minecraft mc) {
        int ev = p.events;
        if (ev == 0 || mc.player == null) return;
        if ((ev & Projection.EVENT_ALL) != 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F, 0.6F));
            mc.player.displayClientMessage(Component.translatable("designandbuild.projection.all_done", p.model.name), true);
        } else if ((ev & Projection.EVENT_STAGE) != 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.6F, 0.4F));
            mc.player.displayClientMessage(Component.translatable("designandbuild.projection.stage_done", stageName(p, p.currentStage())), true);
        } else if ((ev & Projection.EVENT_LAYER) != 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3F, 0.35F));
        }
    }

    public static Component stageName(Projection p, int s) {
        return switch (s) {
            case Projection.STAGE_DEMOLISH -> Component.translatable("designandbuild.stage.demolish");
            case Projection.STAGE_FLOOR -> Component.translatable("designandbuild.stage.floor");
            case Projection.STAGE_PATH -> Component.translatable("designandbuild.stage.path");
            default -> s >= p.stageCount() ? Component.translatable("designandbuild.stage.done")
                    : Component.literal(p.structureName(s));
        };
    }
}
