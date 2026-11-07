package com.example.nobreakdelay;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.entity.DataWatcher;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Определяет, засчитал ли СЕРВЕР блок мечом в момент, когда по тебе попали.
 *
 * Работает в сетевом потоке, до того как пакеты попадут в игру, и опирается
 * не на таймеры и пинг, а на порядок пакетов от сервера:
 *
 *  - Флаг "использует предмет" (бит 0x10 в метаданных #0) для своего игрока в 1.8.9
 *    выставляет ТОЛЬКО сервер. Последнее полученное значение = что думает сервер.
 *  - TCP доставляет пакеты в том порядке, в котором сервер их отправил. Пинг сдвигает
 *    всё целиком, но не переставляет — поэтому сравниваем по порядку, а не по времени.
 *  - Пакет удара (статус 2) сервер шлёт сразу при обработке удара, а изменения
 *    метаданных — в конце того же тика (в трекере сущностей). Поэтому после удара
 *    ждём конец пачки пакетов этого тика и только потом решаем.
 *  - Если флаг блока поменялся в том же тике, что и удар, порядок внутри тика узнать
 *    нельзя — результат UNCERTAIN (звук не играем).
 */
public class BlockhitDetector extends ChannelInboundHandlerAdapter {

    public enum Result { BLOCKED, NOT_BLOCKED, UNCERTAIN }

    public interface Listener {
        void onHit(Result result);
    }

    private static final int USING_ITEM_BIT = 0x10;
    private static final byte STATUS_HURT = 2;
    private static final int SWING_ANIMATION = 0;

    /** Пачка пакетов одного тика приходит почти разом; пауза больше этой = пачка закончилась. */
    private static final long BURST_GAP_MS = 6;
    /** Следующий тик сервера — через ~50 мс; дольше не ждём, чтобы не захватить чужой тик. */
    private static final long HARD_TIMEOUT_MS = 45;
    /** Удар ближнего боя: перед ним кто-то рядом взмахнул рукой. Отсекает огонь, падение и т.п. */
    private static final long SWING_WINDOW_NS = TimeUnit.MILLISECONDS.toNanos(300);

    private static final Field STATUS_ENTITY_ID = ReflectionHelper.findField(
            S19PacketEntityStatus.class, "entityId", "field_149164_a");

    private final Listener listener;

    private int selfId = Integer.MIN_VALUE;
    private int flags = 0;
    private long lastOtherSwingNs = Long.MIN_VALUE / 2;

    // Удар, ожидающий решения
    private boolean pending;
    private int flagsAtHit;
    private boolean selfMetaInWindow;
    private boolean sameByteResent;
    private ScheduledFuture<?> hardTimeout;
    private ScheduledFuture<?> burstTimer;

    public BlockhitDetector(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            inspect(ctx, msg);
        } catch (Throwable ignored) {
            // Детектор не должен ломать соединение ни при каких условиях
        }
        super.channelRead(ctx, msg);
    }

    private void inspect(ChannelHandlerContext ctx, Object msg) throws IllegalAccessException {
        if (msg instanceof S01PacketJoinGame) {
            selfId = ((S01PacketJoinGame) msg).getEntityId();
            flags = 0;
            cancelPending();

        } else if (msg instanceof S0BPacketAnimation) {
            S0BPacketAnimation p = (S0BPacketAnimation) msg;
            if (p.getAnimationType() == SWING_ANIMATION && p.getEntityID() != selfId) {
                lastOtherSwingNs = System.nanoTime();
            }

        } else if (msg instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus p = (S19PacketEntityStatus) msg;
            if (p.getOpCode() == STATUS_HURT && STATUS_ENTITY_ID.getInt(p) == selfId) {
                onHurt(ctx);
            }

        } else if (msg instanceof S1CPacketEntityMetadata) {
            S1CPacketEntityMetadata p = (S1CPacketEntityMetadata) msg;
            if (p.getEntityId() == selfId) {
                onSelfMetadata(p.func_149376_c());
            }
            onTrackerPacket(ctx);

        } else if (msg instanceof S14PacketEntity
                || msg instanceof S18PacketEntityTeleport
                || msg instanceof S19PacketEntityHeadLook) {
            onTrackerPacket(ctx);
        }
    }

    private void onHurt(ChannelHandlerContext ctx) {
        // Предыдущий удар ещё не решён — значит, начался новый тик; решаем его сейчас
        if (pending) resolve();

        if (System.nanoTime() - lastOtherSwingNs > SWING_WINDOW_NS) {
            return; // не удар ближнего боя (огонь, падение, стрела) — не оцениваем
        }

        pending = true;
        flagsAtHit = flags;
        selfMetaInWindow = false;
        sameByteResent = false;
        hardTimeout = ctx.executor().schedule(new Runnable() {
            @Override
            public void run() {
                resolve();
            }
        }, HARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void onSelfMetadata(List<DataWatcher.WatchableObject> entries) {
        if (entries == null) return;
        for (DataWatcher.WatchableObject entry : entries) {
            if (entry.getDataValueId() == 0 && entry.getObject() instanceof Byte) {
                int newFlags = ((Byte) entry.getObject()) & 0xFF;
                if (pending) {
                    selfMetaInWindow = true;
                    // Тот же байт прислан повторно = что-то переключилось туда и обратно за тик
                    if (newFlags == flags) sameByteResent = true;
                }
                flags = newFlags;
            }
        }
    }

    /** Пакеты трекера идут в конце тика. Пока они приходят — продлеваем ожидание. */
    private void onTrackerPacket(ChannelHandlerContext ctx) {
        if (!pending) return;
        if (burstTimer != null) burstTimer.cancel(false);
        burstTimer = ctx.executor().schedule(new Runnable() {
            @Override
            public void run() {
                resolve();
            }
        }, BURST_GAP_MS, TimeUnit.MILLISECONDS);
    }

    private void resolve() {
        if (!pending) return;
        pending = false;
        cancelTimers();

        boolean blockingBefore = (flagsAtHit & USING_ITEM_BIT) != 0;
        boolean blockingAfter = (flags & USING_ITEM_BIT) != 0;

        Result result;
        if (!selfMetaInWindow) {
            result = blockingBefore ? Result.BLOCKED : Result.NOT_BLOCKED;
        } else if (sameByteResent || blockingBefore != blockingAfter) {
            result = Result.UNCERTAIN;
        } else {
            result = blockingBefore ? Result.BLOCKED : Result.NOT_BLOCKED;
        }

        listener.onHit(result);
    }

    private void cancelPending() {
        pending = false;
        cancelTimers();
    }

    private void cancelTimers() {
        if (hardTimeout != null) {
            hardTimeout.cancel(false);
            hardTimeout = null;
        }
        if (burstTimer != null) {
            burstTimer.cancel(false);
            burstTimer = null;
        }
    }
}
