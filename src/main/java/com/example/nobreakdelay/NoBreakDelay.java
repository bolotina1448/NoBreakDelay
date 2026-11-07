package com.example.nobreakdelay;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

/**
 * Две независимые функции:
 *
 * 1. Hit Delay Fix (ВКЛ по умолчанию).
 *    В 1.8 после клика по воздуху Minecraft ставит leftClickCounter = 10 (0,5 с),
 *    и пока счётчик не дойдёт до нуля, клики и удержание ЛКМ игнорируются.
 *    Из-за этого после каждого сломанного блока (следующий клик уходит в воздух)
 *    ломание нового блока начинается с паузой. Мод снимает этот штраф за промах —
 *    как HitDelayFix. Серверу это не видно: пакеты такие же, как у игрока,
 *    который просто не промахнулся.
 *
 * 1b. Legit Break Fix (ВКЛ по умолчанию).
 *    Ванильный счётчик задержки после поломки блока (5 тиков) идёт только пока
 *    ЛКМ зажата на блоке, поэтому после паузы первый блок всё равно ломается с задержкой.
 *    Мод заставляет счётчик идти по времени. Минимум 5 тиков между блоками сохраняется.
 *
 * 2. No Break Delay (ВЫКЛ по умолчанию).
 *    Обнуляет blockHitDelay — ванильные 5 тиков после поломки блока.
 *    Такого поведения у обычного клиента не бывает, сервер это видит,
 *    на PvP-серверах за это банят. Только для одиночной игры.
 *
 * 3. Blockhit Sound (ВКЛ по умолчанию).
 *    Звук наковальни, когда по тебе попали, а сервер в этот момент засчитал блок мечом.
 *    Определяется по пакетам сервера, см. BlockhitDetector. Только чтение — в игре
 *    ничего не меняет и серверу ничего не отправляет.
 */
@Mod(
        modid = NoBreakDelay.MODID,
        name = "No Break Delay",
        version = "1.3.0",
        acceptedMinecraftVersions = "[1.8.9]",
        acceptableRemoteVersions = "*"
)
public class NoBreakDelay {

    public static final String MODID = "nobreakdelay";

    /** Значение, которое Minecraft ставит при промахе. Большие значения (10000)
     *  — это защита от удара сразу после закрытия GUI, её не трогаем. */
    private static final int MISS_PENALTY = 10;

    private static Field leftClickCounterField;
    private static Field blockHitDelayField;

    private static final ResourceLocation ANVIL_SOUND = new ResourceLocation("random.anvil_land");
    private static final float ANVIL_VOLUME = 0.5F;

    private boolean hitDelayFix = true;
    private boolean legitBreakFix = true;
    private boolean noBreakDelay = false;
    private int delayAtTickStart = -1;
    private volatile boolean blockhitSound = true;
    private volatile boolean blockhitDebug = false;

    private KeyBinding hitDelayFixKey;
    private KeyBinding legitBreakFixKey;
    private KeyBinding noBreakDelayKey;
    private KeyBinding blockhitSoundKey;
    private KeyBinding blockhitDebugKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Имена: MCP (dev-среда) и SRG (обычная игра)
        leftClickCounterField = ReflectionHelper.findField(
                Minecraft.class, "leftClickCounter", "field_71429_W");
        blockHitDelayField = ReflectionHelper.findField(
                PlayerControllerMP.class, "blockHitDelay", "field_78781_i");

        hitDelayFixKey = new KeyBinding("Вкл/выкл Hit Delay Fix", Keyboard.KEY_NONE, "No Break Delay");
        legitBreakFixKey = new KeyBinding("Вкл/выкл Legit Break Fix", Keyboard.KEY_NONE, "No Break Delay");
        noBreakDelayKey = new KeyBinding("Вкл/выкл No Break Delay (только одиночка!)", Keyboard.KEY_NONE, "No Break Delay");
        blockhitSoundKey = new KeyBinding("Вкл/выкл звук блокхита", Keyboard.KEY_NONE, "No Break Delay");
        blockhitDebugKey = new KeyBinding("Вкл/выкл лог блокхитов в чат", Keyboard.KEY_NONE, "No Break Delay");
        ClientRegistry.registerKeyBinding(hitDelayFixKey);
        ClientRegistry.registerKeyBinding(legitBreakFixKey);
        ClientRegistry.registerKeyBinding(noBreakDelayKey);
        ClientRegistry.registerKeyBinding(blockhitSoundKey);
        ClientRegistry.registerKeyBinding(blockhitDebugKey);

        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Для каждого подключения к серверу ставим детектор в сетевой конвейер. */
    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        try {
            event.manager.channel().pipeline().addBefore(
                    "packet_handler", "nobreakdelay_blockhit",
                    new BlockhitDetector(new BlockhitDetector.Listener() {
                        @Override
                        public void onHit(final BlockhitDetector.Result result) {
                            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                                @Override
                                public void run() {
                                    handleBlockhit(result);
                                }
                            });
                        }
                    }));
        } catch (Exception ignored) {
            // Нет packet_handler или детектор уже стоит — просто работаем без звука
        }
    }

    /** Вызывается в основном потоке игры. */
    private void handleBlockhit(BlockhitDetector.Result result) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;

        // Флаг "использует предмет" ставится и при еде — считаем только блок мечом
        ItemStack held = player.getHeldItem();
        boolean sword = held != null && held.getItem() instanceof ItemSword;
        if (result == BlockhitDetector.Result.BLOCKED && !sword) {
            result = BlockhitDetector.Result.NOT_BLOCKED;
        }

        if (blockhitSound && result == BlockhitDetector.Result.BLOCKED) {
            Minecraft.getMinecraft().getSoundHandler().playSound(new PositionedSoundRecord(
                    ANVIL_SOUND, ANVIL_VOLUME, 1.0F,
                    (float) player.posX, (float) player.posY, (float) player.posZ));
        }

        if (blockhitDebug) {
            String text;
            switch (result) {
                case BLOCKED:     text = EnumChatFormatting.GREEN + "блок засчитан"; break;
                case NOT_BLOCKED: text = EnumChatFormatting.RED + "блок не засчитан"; break;
                default:          text = EnumChatFormatting.YELLOW + "неясно (блок переключился в тот же тик)"; break;
            }
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Blockhit] " + text));
        }
    }

    /** Срабатывает перед обработкой каждого события мыши — снимает штраф
     *  до того, как Minecraft проверит счётчик при следующем клике. */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        clearMissPenalty();
    }

    /** START и END каждого тика: штраф, выставленный внутри тика,
     *  снимается до того, как удержание ЛКМ проверит его в следующем. */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        clearMissPenalty();
        clearBlockHitDelay();

        if (event.phase == TickEvent.Phase.START) {
            delayAtTickStart = readBlockHitDelay();
        } else {
            tickDownStuckDelay();
        }
    }

    /**
     * Legit Break Fix. В ванилле blockHitDelay уменьшается только внутри
     * onPlayerDamageBlock — то есть только пока ЛКМ зажата на блоке. Отпустил кнопку —
     * счётчик замирает, и следующий блок (даже через 10 секунд) первые 5 тиков не ломается.
     *
     * Здесь счётчик идёт по времени: если за тик игра сама его не уменьшила
     * (и не выставила заново после поломки), уменьшаем на 1. Скорость отсчёта та же,
     * что у игрока, который держит ЛКМ на блоке, поэтому ванильный минимум
     * 5 тиков между блоками сохраняется и тайминги пакетов не выходят за ваниль.
     */
    private void tickDownStuckDelay() {
        if (!legitBreakFix || noBreakDelay) return;
        int now = readBlockHitDelay();
        if (now > 0 && now == delayAtTickStart) {
            writeBlockHitDelay(now - 1);
        }
    }

    private int readBlockHitDelay() {
        PlayerControllerMP controller = Minecraft.getMinecraft().playerController;
        if (controller == null) return -1;
        try {
            return blockHitDelayField.getInt(controller);
        } catch (IllegalAccessException e) {
            return -1;
        }
    }

    private void writeBlockHitDelay(int value) {
        PlayerControllerMP controller = Minecraft.getMinecraft().playerController;
        if (controller == null) return;
        try {
            blockHitDelayField.setInt(controller, value);
        } catch (IllegalAccessException ignored) {
        }
    }

    private void clearMissPenalty() {
        if (!hitDelayFix) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            int counter = leftClickCounterField.getInt(mc);
            if (counter > 0 && counter <= MISS_PENALTY) {
                leftClickCounterField.setInt(mc, 0);
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    private void clearBlockHitDelay() {
        if (!noBreakDelay) return;
        PlayerControllerMP controller = Minecraft.getMinecraft().playerController;
        if (controller == null) return;
        try {
            if (blockHitDelayField.getInt(controller) > 0) {
                blockHitDelayField.setInt(controller, 0);
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (hitDelayFixKey.isPressed()) {
            hitDelayFix = !hitDelayFix;
            say("Hit Delay Fix", hitDelayFix);
        }
        if (legitBreakFixKey.isPressed()) {
            legitBreakFix = !legitBreakFix;
            say("Legit Break Fix", legitBreakFix);
        }
        if (noBreakDelayKey.isPressed()) {
            noBreakDelay = !noBreakDelay;
            say("No Break Delay", noBreakDelay);
        }
        if (blockhitSoundKey.isPressed()) {
            blockhitSound = !blockhitSound;
            say("Звук блокхита", blockhitSound);
        }
        if (blockhitDebugKey.isPressed()) {
            blockhitDebug = !blockhitDebug;
            say("Лог блокхитов", blockhitDebug);
        }
    }

    private void say(String feature, boolean on) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(new ChatComponentText(
                EnumChatFormatting.GRAY + "[" + feature + "] " +
                (on ? EnumChatFormatting.GREEN + "включён" : EnumChatFormatting.RED + "выключен")));
    }
}
