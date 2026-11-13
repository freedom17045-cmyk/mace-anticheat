package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

/**
 * Detects stun-slam macros (axe hit -> hotbar swap -> mace smash on the same target).
 * Flags EVERY time a physically impossible or too-fast action happens.
 * Timing uses System.nanoTime() on packet arrival (millisecond-accurate, not tick-bound).
 *
 * NOTE: written against Grim's public source layout, NOT compiled. API names marked TODO
 * may differ slightly in 2.3.74; fix any compile errors in the Grim repo.
 */
@CheckData(name = "StunSlamMacro", description = "Automated axe->mace stun slam", experimental = true)
public class StunSlamMacro extends Check implements PreViaPacketReceiveListener {

    // ---- Tunables ----
    private static final double IMPOSSIBLE_MS = 50.0;        // under 1 tick: not humanly possible
    private static final double REACTION_THRESHOLD_MS = 250.0; // human reaction floor, flag below this
    private static final double COMBO_WINDOW_MS = 1000.0;    // longer gaps aren't a combo
    private static final int MAX_PING_MS = 250;              // skip laggy players (packets get batched)
    private static final int HISTORY = 6;

    // Swap->attack cooldown detection (mace / spear)
    private static final int MACE_COOLDOWN_TICKS = 33;   // estimate: mace attack speed ~0.6
    private static final int SPEAR_COOLDOWN_TICKS = 20;  // estimate, tune per spear tier
    private static final int EARLY_ATTACK_LIMIT = 3;     // consecutive early attacks before flagging
    private int earlyAttackStreak = 0;

    // Client tick counter: the client sends one movement packet per tick, so counting them gives client ticks
    private long tick = 0L;
    private long axeTick = 0L;
    private long swapTick = 0L;

    private long lastAxeHit = 0L;
    private int lastAxeTarget = -1;
    private long lastSwap = 0L;
    private final long[] comboGaps = new long[HISTORY];
    private int gapCount = 0;

    public StunSlamMacro(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        long now = System.nanoTime();

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            tick++;
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            lastSwap = now;
            swapTick = tick;
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;

        // TODO: confirm accessor; Grim exposes the held item through player.getInventory()
        ItemType held = player.getInventory().getHeldItem().getType();

        if (isAxe(held)) {
            lastAxeHit = now;
            axeTick = tick;
            lastAxeTarget = wrapper.getEntityId();
            return;
        }

        boolean spear = isSpear(held);
        if (held == ItemTypes.MACE || spear) {
            checkSwapAttack(spear ? "spear" : "mace", spear ? SPEAR_COOLDOWN_TICKS : MACE_COOLDOWN_TICKS);
        }
        if (spear) return;

        if (held != ItemTypes.MACE) return;
        if (wrapper.getEntityId() != lastAxeTarget) return;
        if (player.fallDistance < 1.5) return; // TODO: confirm field name; must be a real smash attack

        double comboMs = (now - lastAxeHit) / 1_000_000.0; // axe hit -> slam
        double swapMs = (now - lastSwap) / 1_000_000.0;    // hotbar swap -> slam
        lastAxeTarget = -1; // consume the combo

        if (comboMs > COMBO_WINDOW_MS) return; // not a combo

        // Log the timing of EVERY stun slam, flagged or not
        long pingMs = player.getTransactionPing() / 1_000_000; // TODO: confirm ping accessor
        report(String.format("tick=%d axe->slam=%d ticks swap->slam=%d ticks (%.1fms/%.1fms) fall=%.2f ping=%dms target=%d",
                tick, tick - axeTick, tick - swapTick, comboMs, swapMs,
                player.fallDistance, pingMs, wrapper.getEntityId()));

        if (pingMs > MAX_PING_MS) return;

        // IMPOSSIBLE 1: axe hit and mace slam less than one tick apart
        if (comboMs < IMPOSSIBLE_MS) {
            flag(); // TODO: if flag() needs a verbose writer in 2.3.74, copy BadPacketsA's pattern
        }

        // IMPOSSIBLE 2: hotbar swap and slam less than one tick apart (swap+attack in one instant)
        if (swapMs < IMPOSSIBLE_MS) {
            flag();
        }

        // IMPOSSIBLE 2b: swap and slam on the same client tick (a true "0 tick" stun slam)
        if (tick - swapTick == 0 && tick - axeTick <= 1) {
            flag();
        }

        // IMPOSSIBLE 3: mace slam with NO hotbar swap since the axe hit (item changed without a swap)
        if (lastSwap < lastAxeHit) {
            flag();
        }

        // TOO FAST: reaction time under the human floor
        if (comboMs < REACTION_THRESHOLD_MS) {
            flag();
        }

        // ROBOTIC: six combos in a row with near-identical timing (<2ms std dev)
        comboGaps[gapCount++ % HISTORY] = (long) comboMs;
        if (gapCount >= HISTORY) {
            double avg = 0;
            for (long g : comboGaps) avg += g;
            avg /= HISTORY;
            double var = 0;
            for (long g : comboGaps) var += (g - avg) * (g - avg);
            var /= HISTORY;
            if (var < 4.0 && avg < 400) {
                flag();
            }
        }
    }

    /**
     * Mace/spear swap detection. Swapping items resets the attack cooldown in vanilla, so a
     * swap -> attack on the same tick (0 tick), or repeatedly attacking inside the cooldown window
     * right after swapping, indicates a swap macro / cooldown bypass.
     */
    private void checkSwapAttack(String weapon, int cooldownTicks) {
        long sinceSwap = tick - swapTick;
        double sinceSwapMs = (System.nanoTime() - lastSwap) / 1_000_000.0;
        if (swapTick == 0L && lastSwap == 0L) return; // never swapped yet

        report(weapon + " swap->attack=" + sinceSwap + " ticks (" + String.format("%.1f", sinceSwapMs) + "ms)");

        // IMPOSSIBLE: swap and attack on the same tick, or under one tick apart
        if (sinceSwap == 0 || sinceSwapMs < IMPOSSIBLE_MS) {
            flag(); // TODO: verbose writer, see BadPacketsA
        }

        // REACTION TIME (mace): swap -> attack faster than the human reaction floor
        if (weapon.equals("mace") && sinceSwapMs < REACTION_THRESHOLD_MS) {
            flag();
        }

        // COOLDOWN BYPASS: attacked inside the cooldown window right after swapping, several times in a row
        if (sinceSwap < cooldownTicks) {
            if (++earlyAttackStreak >= EARLY_ATTACK_LIMIT) {
                flag();
            }
        } else {
            earlyAttackStreak = 0;
        }
    }

    private static boolean isSpear(ItemType type) {
        // TODO: confirm; PacketEvents may expose ItemTypes.*_SPEAR constants, name check is version-safe
        return type.getName().getKey().endsWith("_spear");
    }

    private void report(String info) {
        // TODO: confirm in repo; Grim logs via LogUtil (ac.grim.grimac.utils.anticheat.LogUtil)
        ac.grim.grimac.utils.anticheat.LogUtil.info("[StunSlam] " + player.getName() + " " + info
                + " @" + System.currentTimeMillis());
    }

    private static boolean isAxe(ItemType type) {
        return type == ItemTypes.WOODEN_AXE || type == ItemTypes.STONE_AXE
                || type == ItemTypes.IRON_AXE || type == ItemTypes.GOLDEN_AXE
                || type == ItemTypes.DIAMOND_AXE || type == ItemTypes.NETHERITE_AXE;
    }
}
