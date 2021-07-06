package emortal.lazertag.game

import emortal.lazertag.gun.Gun
import emortal.lazertag.maps.MapManager
import emortal.lazertag.utils.MinestomRunnable
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.entity.Entity
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.sound.SoundEvent
import net.minestom.server.tag.Tag
import net.minestom.server.timer.Task
import net.minestom.server.utils.Position
import net.minestom.server.utils.Vector
import net.minestom.server.utils.time.TimeUnit
import world.cepi.kstom.Manager
import world.cepi.kstom.adventure.asMini
import world.cepi.kstom.adventure.sendMiniMessage
import world.cepi.kstom.util.component1
import world.cepi.kstom.util.component2
import world.cepi.kstom.util.component3
import java.time.Duration
import kotlin.properties.Delegates

class Game(val id: Int, val options: GameOptions) {
    val instance: Instance = MapManager.mapMap[options.map]!!

    val players: MutableSet<Player> = HashSet()

    val playerAudience: Audience = Audience.audience(players)
    var gameState: GameState = GameState.WAITING_FOR_PLAYERS

    // TODO: Maps
    private var startingTask: Task? = null

    val scoreboard: Sidebar = Sidebar("<gradient:gold:yellow>LazerTag".asMini())

    val respawnTasks: ArrayList<Task> = ArrayList()
    val reloadTasks: HashMap<Player, Task> = HashMap()

    var startTime by Delegates.notNull<Long>()

    init {
        scoreboard.createLine(Sidebar.ScoreboardLine("header", Component.empty(), 30))
        scoreboard.createLine(Sidebar.ScoreboardLine("footer", Component.empty(), 0))
        scoreboard.createLine(Sidebar.ScoreboardLine("ipLine", Component.text("mc.emortal.dev --------", NamedTextColor.DARK_GRAY), -1))
    }

    fun addPlayer(player: Player) {
        scoreboard.addViewer(player)
        players.add(player)
        playerAudience.sendMiniMessage(" <gray>[<green><bold>+</bold></green>]</gray> ${player.username} <green>joined</green>")

        player.setTag(Tag.Integer("kills"), 0)

        player.respawnPoint = getRandomRespawnPosition()
        if (player.instance!! != instance) player.setInstance(instance)
        player.gameMode = GameMode.SPECTATOR

        if (gameState == GameState.PLAYING) {
            respawn(player)
            return
        }

        if (players.size == options.maxPlayers) {
            start()
            return
        }

        if (players.size >= options.playersToStart) {
            if (startingTask != null) return

            gameState = GameState.STARTING

            startingTask = object : MinestomRunnable() {
                var secs = 5

                override fun run() {
                    if (secs < 1) {
                        cancel()
                        start()
                        return
                    }

                    playerAudience.playSound(Sound.sound(SoundEvent.WOODEN_BUTTON_CLICK_ON, Sound.Source.BLOCK, 1f, 1f))
                    playerAudience.showTitle(
                        Title.title(
                            Component.text(secs, NamedTextColor.GREEN, TextDecoration.BOLD),
                            Component.empty(),
                            Title.Times.of(
                                Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(250)
                            )
                        )
                    )

                    secs--
                }
            }.repeat(Duration.ofSeconds(1)).schedule()
        }
    }

    fun removePlayer(player: Player) {
        players.remove(player)
        playerAudience.sendMiniMessage(" <gray>[<red><bold>-</bold></red>]</gray> ${player.username} <red>left</red>")
    }

    private fun start() {
        startTime = System.currentTimeMillis()

        startingTask = null
        gameState = GameState.PLAYING

        players.forEach(::respawn)

        refreshScoreboard()
    }

    fun kill(player: Player, killer: Player?) {
        if (gameState == GameState.ENDING) return

        player.inventory.clear()
        player.velocity = Vector(0.0, 0.0, 0.0)
        player.gameMode = GameMode.SPECTATOR
        player.isInvisible = true
        player.setNoGravity(true)

        reloadTasks[player]?.cancel()
        reloadTasks.remove(player)

        if (killer != null && killer != player) {
            val lookAtVector = player.position.toVector().subtract(killer.position.toVector())

            player.teleport(player.position.clone().setDirection(lookAtVector.clone().multiply(-1)))
            player.velocity = lookAtVector.normalize().multiply(15)

            val currentKills = killer.getTag(Tag.Integer("kills"))!! + 1

            if (currentKills >= 20) return victory(killer)

            killer.setTag(Tag.Integer("kills"), currentKills)

            // problematic:  with ${Gun.registeredMap[killer.itemInMainHand.meta.customModelData]!!.name}
            playerAudience.sendMiniMessage(" <red>☠</red> <dark_gray>|</dark_gray> <gray><white>${killer.username}</white> killed <red>${player.username}</red>")
            killer.playSound(Sound.sound(SoundEvent.NOTE_BLOCK_PLING, Sound.Source.PLAYER, 1f, 1f))

            player.showTitle(Title.title(
                Component.text("YOU DIED", NamedTextColor.RED, TextDecoration.BOLD),
                "<gray>Killed by <red><bold>${killer.username}</bold></red>".asMini(),
                Title.Times.of(
                    Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)
                )
            ))

            val gun = Gun.registeredMap.values.random()
            killer.inventory.setItemStack(0, gun.item)
            gun.renderAmmo(killer, gun.ammo)

            refreshScoreboard()
        } else {

            playerAudience.sendMiniMessage(" <red>☠</red> <dark_gray>|</dark_gray> <gray><red>${player.username}</red> killed themselves")

            player.showTitle(Title.title(
                Component.text("YOU DIED", NamedTextColor.RED, TextDecoration.BOLD),
                "<rainbow>You killed yourself!".asMini(),
                Title.Times.of(
                    Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)
                )
            ))

            val currentKills = player.getTag(Tag.Integer("kills"))!! - 1

            if (currentKills > 0) player.setTag(Tag.Integer("kills"), currentKills)
        }


        respawnTasks.add(object : MinestomRunnable() {
            var i = 3

            override fun run() {
                if (i == 3) {
                    player.velocity = Vector(0.0, 0.0, 0.0)
                    if (killer != null && !killer.isDead && killer != player) player.spectate(killer)
                }
                if (i <= 0) {
                    respawn(player)

                    cancel()
                    return
                }

                val (x, y, z) = killer?.position ?: player.position
                player.playSound(Sound.sound(SoundEvent.WOODEN_BUTTON_CLICK_ON, Sound.Source.BLOCK, 1f, 1f), x, y, z)
                player.showTitle(Title.title(
                    Component.text(i, NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.empty(),
                    Title.Times.of(
                        Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)
                    )
                ))

                i--
            }
        }.delay(Duration.ofSeconds(2)).repeat(Duration.ofSeconds(1)).schedule())
    }
    private fun respawn(player: Player) = with(player) {
        inventory.clear()
        health = 20f
        teleport(getRandomRespawnPosition())
        stopSpectating()
        isInvisible = false
        gameMode = GameMode.ADVENTURE
        setNoGravity(false)
        clearEffects()

        if (gameState == GameState.ENDING) return

        // TODO: Replace with proper gun score system

        val gun = Gun.registeredMap.values.random()
        inventory.setItemStack(0, gun.item)
        gun.renderAmmo(this, gun.ammo)

    }

    private fun victory(player: Player) {
        if (gameState == GameState.ENDING) return
        gameState = GameState.ENDING

        reloadTasks.values.forEach(Task::cancel)
        reloadTasks.clear()

        instance.entities
            .filter { it !is Player }
            .forEach(Entity::remove)

        players.forEach { it.inventory.clear() }

        val message = Component.text()
            .append(Component.text("VICTORY", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text("\n${player.username} won the game", NamedTextColor.WHITE))
            .build()

        playerAudience.sendMessage(message)

        Manager.scheduler.buildTask { destroy() }
            .delay(12, TimeUnit.SECOND).schedule()
    }

    private fun destroy() {
        GameManager.deleteGame(this)

        respawnTasks.forEach(Task::cancel)
        respawnTasks.clear()
        players.forEach { it ->
            GameManager.addPlayer(it)
            scoreboard.removeViewer(it)
        }
        players.clear()
    }

    private fun refreshScoreboard() {
        // Clear all scoreboard lines other than header and footer
        for (i in 1..12) {
            scoreboard.removeLine("line$i")
        }

        val killMap = HashMap<Player, Int>()

        players.forEach {
            killMap[it] = it.getTag(Tag.Integer("kills"))!!
        }

        val sortedKillMap = killMap.toList().sortedBy { (_, v) -> v }.reversed().take(12).toMap()

        var i = 1
        sortedKillMap.forEach { (player, kills) ->
            if (kills == 0) return

            scoreboard.createLine(Sidebar.ScoreboardLine(
                player.uuid.toString(),

                Component.text()
                    .append(Component.text(player.username, NamedTextColor.GRAY))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(kills, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                    .build(),

                kills
            ))

            i++
        }

    }

    private fun getRandomRespawnPosition(): Position = MapManager.spawnPositionMap[options.map]!!.random()

    fun isFull(): Boolean = players.size >= options.maxPlayers
}