package com.dreamfallen.nexo

import com.dreamfallen.nexo.bridge.PaperBridge
import com.dreamfallen.nexo.bridge.PaperBridge.EventWrapper
import com.dreamfallen.nexo.bridge.PaperBridge.PlayerWrapper
import com.dreamfallen.nexo.bridge.PaperBridge.SenderWrapper
import com.dreamfallen.nexo.runtime.interpreter.Interpreter
import com.dreamfallen.nexo.runtime.interpreter.ModuleRegistry
import com.dreamfallen.nexo.runtime.loader.ScriptLoader
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.nio.file.Files

class NexoPlugin : JavaPlugin(), Listener {
    private lateinit var loader: ScriptLoader
    private lateinit var interpreter: Interpreter
    private val moduleRegistry = ModuleRegistry()
    private val scheduledTasks = ArrayList<BukkitTask>()
    private val registeredDynamicCommands = HashSet<String>()

    override fun onEnable() {
        val scriptsDir = dataFolder.toPath().resolve("scripts")
        if (!Files.exists(scriptsDir)) {
            Files.createDirectories(scriptsDir)
            writeDefaultExample(scriptsDir.resolve("welcome.nx"))
        }

        registerBuiltInModules()
        loader = ScriptLoader(scriptsDir, logger)
        interpreter = Interpreter(PaperBridge.createGlobals(logger), moduleRegistry)

        server.pluginManager.registerEvents(this, this)
        reloadScripts()
        logger.info("Nexo enabled.")
    }

    override fun onDisable() {
        scheduledTasks.forEach { it.cancel() }
        scheduledTasks.clear()
        logger.info("Nexo disabled.")
    }

    fun reloadScripts(): Int {
        scheduledTasks.forEach { it.cancel() }
        scheduledTasks.clear()
        interpreter.clearRegistrations()
        loader.invalidate()

        val programs = loader.loadAll()
        for (program in programs) {
            try {
                interpreter.execute(program)
            } catch (e: Exception) {
                logger.severe("Runtime error in '${program.sourceName}': ${e.message}")
            }
        }

        syncDynamicCommands()
        syncSchedules()
        return programs.size
    }

    private fun registerBuiltInModules() {
        // Register core module stubs so `require(skbee)` etc. resolve cleanly
        listOf("skbee", "skquery", "skellett", "skrayfall", "tuske").forEach { modName ->
            moduleRegistry.register(ModuleRegistry.NexoModule(modName, emptyMap()))
        }
    }

    private fun syncDynamicCommands() {
        val commandMap = server.commandMap
        for ((name, cmdData) in interpreter.registeredCommands()) {
            if (registeredDynamicCommands.add(name.lowercase())) {
                val bukkitCmd = object : BukkitCommand(name) {
                    init {
description = cmdData.options["description"]?.toString() ?: "Nexo script command"
usageMessage = cmdData.options["usage"]?.toString() ?: "/$name"
permission = cmdData.options["permission"]?.toString()
}
override fun execute(sender: CommandSender, commandLabel: String, args: Array): Boolean {
val wrappedSender = if (sender is Player) PlayerWrapper(sender) else SenderWrapper(sender)
try {
interpreter.dispatchCommand(name, wrappedSender, args.toList())
} catch (e: Exception) {
sender.sendMessage(PaperBridge.colorize("&cScript error: ${e.message}"))
logger.severe("Error executing /$name: ${e.message}")
}
return true
}
}
commandMap.register("nexo", bukkitCmd)
}
}
}
private fun syncSchedules() {
for (schedule in interpreter.registeredSchedules()) {
val ticks = parseIntervalTicks(schedule.interval)
if (ticks <= 0L) {
logger.warning("Invalid @every interval: ${schedule.interval}")
continue
}
val task = Bukkit.getScheduler().runTaskTimer(this, Runnable {
try {
interpreter.dispatch("_schedule${schedule.hashCode()}", null)
} catch (e: Exception) {
logger.severe("Schedule execution error: ${e.message}")
}
}, ticks, ticks)
scheduledTasks += task
}
}
private fun parseIntervalTicks(raw: Any?): Long {
if (raw is Number) return raw.toLong()
val str = raw?.toString()?.trim()?.lowercase() ?: return -1L
val parts = str.split(" ", limit = 2)
val num = parts[0].toLongOrNull() ?: return -1L
val unit = parts.getOrNull(1) ?: "ticks"
return when {
unit.startsWith("tick") -> num
unit.startsWith("sec") -> num * 20L
unit.startsWith("min") -> num * 20L * 60L
unit.startsWith("hour") -> num * 20L * 3600L
else -> -1L
}
}
override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array): Boolean {
if (command.name.equals("nexo", ignoreCase = true)) {
when (args.firstOrNull()?.lowercase()) {
"reload" -> {
val count = reloadScripts()
sender.sendMessage(PaperBridge.colorize("&aReloaded $count Nexo script(s)."))
}
"list" -> {
val cmds = interpreter.registeredCommands().keys.joinToString(", ").ifEmpty { "none" }
val evts = interpreter.registeredEvents().joinToString(", ").ifEmpty { "none" }
sender.sendMessage(PaperBridge.colorize("&bEvents: &f$evts &7| &bCommands: &f$cmds"))
}
else -> sender.sendMessage(PaperBridge.colorize("&eUsage: /nexo <reload|list>"))
}
return true
}
return false
}
private fun safeDispatch(eventName: String, wrapper: EventWrapper) {
try {
interpreter.dispatch(eventName, wrapper)
} catch (e: Exception) {
logger.severe("Error handling event '$eventName': ${e.message}")
}
}
@EventHandler
fun onJoin(e: PlayerJoinEvent) = safeDispatch("player_join", EventWrapper(e))
@EventHandler
fun onQuit(e: PlayerQuitEvent) = safeDispatch("player_quit", EventWrapper(e))
@EventHandler
fun onChat(e: AsyncChatEvent) {
val msg = PlainTextComponentSerializer.plainText().serialize(e.message())
safeDispatch("player_chat", EventWrapper(e, mapOf("player" to PlayerWrapper(e.player), "message" to msg)))
}
@EventHandler
fun onBreak(e: BlockBreakEvent) = safeDispatch("block_break", EventWrapper(e, mapOf("player" to PlayerWrapper(e.player))))
@EventHandler
fun onPlace(e: BlockPlaceEvent) = safeDispatch("block_place", EventWrapper(e, mapOf("player" to PlayerWrapper(e.player))))
@EventHandler
fun onInteract(e: PlayerInteractEvent) = safeDispatch("player_interact", EventWrapper(e))
@EventHandler
fun onDeath(e: PlayerDeathEvent) = safeDispatch("player_death", EventWrapper(e, mapOf("player" to PlayerWrapper(e.entity))))
private fun writeDefaultExample(path: java.nio.file.Path) {
Files.writeString(
path,
"""
using py
require(skbee)
@event("player_join")
def on_join(event):
player = event.player
player.send_message(f"&aWelcome to the server, {player.name}!")
player.give(item("diamond_sword", amount=1))
""".trimIndent()
)
}
}
