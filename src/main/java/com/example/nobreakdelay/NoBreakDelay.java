package com.example.nobreakdelay;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
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
 * 2. No Break Delay (ВЫКЛ по умолчанию).
 *    Обнуляет blockHitDelay — ванильные 5 тиков после поломки блока.
 *    Такого поведения у обычного клиента не бывает, сервер это видит,
 *    на PvP-серверах за это банят. Только для одиночной игры.
 */
@Mod(
        modid = NoBreakDelay.MODID,
        name = "No Break Delay",
        version = "1.1.0",
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

    private boolean hitDelayFix = true;
    private boolean noBreakDelay = false;

    private KeyBinding hitDelayFixKey;
    private KeyBinding noBreakDelayKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Имена: MCP (dev-среда) и SRG (обычная игра)
        leftClickCounterField = ReflectionHelper.findField(
                Minecraft.class, "leftClickCounter", "field_71429_W");
        blockHitDelayField = ReflectionHelper.findField(
                PlayerControllerMP.class, "blockHitDelay", "field_78781_i");

        hitDelayFixKey = new KeyBinding("Вкл/выкл Hit Delay Fix", Keyboard.KEY_NONE, "No Break Delay");
        noBreakDelayKey = new KeyBinding("Вкл/выкл No Break Delay (только одиночка!)", Keyboard.KEY_NONE, "No Break Delay");
        ClientRegistry.registerKeyBinding(hitDelayFixKey);
        ClientRegistry.registerKeyBinding(noBreakDelayKey);

        MinecraftForge.EVENT_BUS.register(this);
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
        if (noBreakDelayKey.isPressed()) {
            noBreakDelay = !noBreakDelay;
            say("No Break Delay", noBreakDelay);
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
