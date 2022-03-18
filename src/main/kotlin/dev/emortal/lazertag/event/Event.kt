package dev.emortal.lazertag.event

import dev.emortal.lazertag.game.LazerTagGame
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.time.Duration
import java.util.*
import kotlin.reflect.full.primaryConstructor

sealed class Event {

    companion object {
        private val constructors = Event::class.sealedSubclasses.mapNotNull { it.primaryConstructor }
        fun createRandomEvent(): Event {
            return constructors.random().call()
        }

        private val prefix = Component.text()
            .append(Component.text("EVENT", NamedTextColor.YELLOW, TextDecoration.BOLD))
            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
    }

    var running = false

    abstract val duration: Duration
    abstract val startMessage: Component

    fun performEvent(game: LazerTagGame) {
        running = true
        eventStarted(game)

        game.sendMessage(Component.text().append(prefix).append(startMessage))

        val task = object : TimerTask() {
            override fun run() {
                eventEnded(game)
            }
        }
        game.timer.schedule(task, duration.toMillis())
    }


    abstract fun eventStarted(game: LazerTagGame)
    abstract fun eventEnded(game: LazerTagGame)

}