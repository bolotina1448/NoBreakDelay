package com.example.nobreakdelay;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
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
 * Убирает ванильную задержку (5 тиков = 250 мс) между сломанным блоком
 * и началом ломания следующего.
 *
 * В ванилле PlayerControllerMP после поломки блока ставит blockHitDelay = 5,
 * и следующие 5 тиков onPlayerDamageBlock() ничего не делает, кроме уменьшения
 * счётчика. Мод сбрасывает его в 0 в начале и в конце каждого клиентского тика,
 * поэтому следующий блок начинает ломаться уже в следующем тике.
 */
@Mod(
        modid = NoBreakDelay.MODID,
        name = "No Break Delay",
        version = "1.0",
        acceptedMinecraftVersions = "[1.8.9]",
        acceptableRemoteVersions = "*"
)
public class NoBreakDelay {

    public static final String MODID = "nobreakdelay";

    private static Field blockHitDelayField;

    private boolean enabled = true;
    private KeyBinding toggleKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // "blockHitDelay" — имя в dev-среде (MCP), "field_78781_i" — SRG-имя в обычной игре
        blockHitDelayField = ReflectionHelper.findField(
                PlayerControllerMP.class, "blockHitDelay", "field_78781_i");

        // Клавиша вкл/выкл — назначается в Настройки → Управление → "No Break Delay"
        toggleKey = new KeyBinding("Вкл/выкл No Break Delay", Keyboard.KEY_NONE, "No Break Delay");
        ClientRegistry.registerKeyBinding(toggleKey);

        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // Срабатывает и на START, и на END: задержка, выставленная внутри тика,
        // обнуляется до того, как следующий тик проверит её.
        if (!enabled) return;

        PlayerControllerMP controller = Minecraft.getMinecraft().playerController;
        if (controller == null) return;

        try {
            if (blockHitDelayField.getInt(controller) > 0) {
                blockHitDelayField.setInt(controller, 0);
            }
        } catch (IllegalAccessException ignored) {
            // ReflectionHelper уже сделал поле доступным, сюда не попадём
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!toggleKey.isPressed()) return;

        enabled = !enabled;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.GRAY + "[NoBreakDelay] " +
                    (enabled ? EnumChatFormatting.GREEN + "включён" : EnumChatFormatting.RED + "выключен")));
        }
    }
}
