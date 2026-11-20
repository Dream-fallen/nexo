package com.dreamfallen.nexo.bridge

import com.dreamfallen.nexo.runtime.interpreter.Interpreter.HostObject
import com.dreamfallen.nexo.runtime.interpreter.Interpreter.NativeFunction
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import java.util.logging.Logger

/** Reflection-free wrappers exposing Paper/Bukkit APIs to Nexo scripts. */
object PaperBridge {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun colorize(text: String) = legacy.deserialize(text)

    fun createGlobals(logger: Logger): Map<String, Any?> = mapOf(
        "print" to NativeFunction { p, _ ->
            logger.info(p.joinToString(" ") { it?.toString() ?: "null" })
            null
        },
        "broadcast" to NativeFunction { p, _ ->
            val msg = p.firstOrNull()?.toString() ?: ""
            Bukkit.broadcast(colorize(msg))
            null
        },
        "item" to NativeFunction { p, n ->
            val rawMat = p.getOrNull(0)?.toString()?.uppercase()
                ?: throw IllegalArgumentException("item() requires a material name")
            val mat = Material.matchMaterial(rawMat)
                ?: throw IllegalArgumentException("Unknown material '$rawMat'")

            val opts = p.getOrNull(1) as? Map<*, *>
            val amount = ((n["amount"] ?: opts?.get("amount")) as? Number)?.toInt() ?: 1
            val name = (n["name"] ?: opts?.get("name"))?.toString()

            val stack = ItemStack(mat, amount)
            if (name != null) {
                val meta = stack.itemMeta
                meta.displayName(colorize(name))
                stack.itemMeta = meta
            }
            ItemWrapper(stack)
        }
    )

    class PlayerWrapper(val player: Player) : HostObject {
        override fun getMember(name: String): Any? = when (name) {
            "name" -> player.name
            "uuid" -> player.uniqueId.toString()
            "health" -> player.health
            "food", "food_level", "foodLevel" -> player.foodLevel
            "op", "is_op", "isOp" -> player.isOp
            "send_message", "sendMessage" -> NativeFunction { p, _ ->
                player.sendMessage(colorize(p.firstOrNull()?.toString() ?: ""))
                null
            }
            "give" -> NativeFunction { p, _ ->
                val itemArg = p.firstOrNull()
                val stack = when (itemArg) {
                    is ItemWrapper -> itemArg.stack.clone()
                    is ItemStack -> itemArg.clone()
                    is String -> ItemStack(Material.matchMaterial(itemArg.uppercase())
                        ?: throw IllegalArgumentException("Unknown material '$itemArg'"))
                    else -> throw IllegalArgumentException("give() expects an item or material name")
                }
                player.inventory.addItem(stack)
                null
            }
            "kick" -> NativeFunction { p, _ ->
                player.kick(colorize(p.firstOrNull()?.toString() ?: "Kicked from server"))
                null
            }
            "has_permission", "hasPermission" -> NativeFunction { p, _ ->
                player.hasPermission(p.firstOrNull()?.toString() ?: "")
            }
            else -> throw IllegalArgumentException("Player has no property '$name'")
        }

        override fun setMember(name: String, value: Any?) {
            when (name) {
                "health" -> player.health = (value as Number).toDouble()
                "food", "food_level", "foodLevel" -> player.foodLevel = (value as Number).toInt()
                else -> super.setMember(name, value)
            }
        }
    }

    class SenderWrapper(val sender: CommandSender) : HostObject {
        override fun getMember(name: String): Any? = when (name) {
            "name" -> sender.name
            "send_message", "sendMessage" -> NativeFunction { p, _ ->
                sender.sendMessage(colorize(p.firstOrNull()?.toString() ?: ""))
                null
            }
            "has_permission", "hasPermission" -> NativeFunction { p, _ ->
                sender.hasPermission(p.firstOrNull()?.toString() ?: "")
            }
            else -> throw IllegalArgumentException("Sender has no property '$name'")
        }
    }

    class ItemWrapper(val stack: ItemStack) : HostObject {
        override fun getMember(name: String): Any? = when (name) {
            "type" -> stack.type.name.lowercase()
            "amount" -> stack.amount
            else -> throw IllegalArgumentException("Item has no property '$name'")
        }
    }

    class EventWrapper(val event: Event, private val extras: Map<String, Any?> = emptyMap()) : HostObject {
        override fun getMember(name: String): Any? {
            if (extras.containsKey(name)) return extras[name]
            if (name == "player" && event is PlayerEvent) return PlayerWrapper(event.player)
            if (name == "cancelled" || name == "is_cancelled" || name == "isCancelled") {
                return (event as? Cancellable)?.isCancelled ?: false
            }
            if (name == "cancel") {
                return NativeFunction { p, _ ->
                    val cancellable = event as? Cancellable
                        ?: throw IllegalArgumentException("Event is not cancellable")
                    cancellable.isCancelled = (p.firstOrNull() as? Boolean) ?: true
                    null
                }
            }
            throw IllegalArgumentException("Event has no property '$name'")
        }

        override fun setMember(name: String, value: Any?) {
            if (name == "cancelled" || name == "is_cancelled" || name == "isCancelled") {
                val cancellable = event as? Cancellable
                    ?: throw IllegalArgumentException("Event is not cancellable")
                cancellable.isCancelled = value as Boolean
                return
            }
            super.setMember(name, value)
        }
    }
}
