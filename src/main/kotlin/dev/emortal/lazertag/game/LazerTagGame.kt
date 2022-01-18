package dev.emortal.lazertag.game

import dev.emortal.immortal.game.EndGameQuotes
import dev.emortal.immortal.game.GameOptions
import dev.emortal.immortal.game.GameState
import dev.emortal.immortal.game.PvpGame
import dev.emortal.immortal.util.MinestomRunnable
import dev.emortal.immortal.util.reset
import dev.emortal.lazertag.LazerTagExtension
import dev.emortal.lazertag.game.LazerTagPlayerHelper.cleanup
import dev.emortal.lazertag.game.LazerTagPlayerHelper.hasSpawnProtection
import dev.emortal.lazertag.game.LazerTagPlayerHelper.kills
import dev.emortal.lazertag.game.LazerTagPlayerHelper.spawnProtectionMillis
import dev.emortal.lazertag.gun.Gun
import dev.emortal.lazertag.gun.Gun.Companion.ammoTag
import dev.emortal.lazertag.gun.Gun.Companion.heldGun
import dev.emortal.lazertag.gun.Gun.Companion.lastShotTag
import dev.emortal.lazertag.gun.Gun.Companion.reloadingTag
import dev.emortal.lazertag.gun.ProjectileGun
import dev.emortal.lazertag.utils.cancel
import dev.emortal.lazertag.utils.setCooldown
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.entity.damage.EntityDamage
import net.minestom.server.entity.metadata.item.ItemEntityMeta
import net.minestom.server.entity.metadata.other.ArmorStandMeta
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.entity.EntityTickEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.player.*
import net.minestom.server.instance.AnvilLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.sound.SoundEvent
import net.minestom.server.utils.time.TimeUnit
import world.cepi.kstom.Manager
import world.cepi.kstom.adventure.asMini
import world.cepi.kstom.event.listenOnly
import world.cepi.kstom.item.and
import world.cepi.kstom.item.item
import world.cepi.kstom.util.playSound
import java.text.DecimalFormat
import java.time.Duration
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.floor

class LazerTagGame(gameOptions: GameOptions) : PvpGame(gameOptions) {

    companion object {
        val destructableBlocks = listOf(Block.GRASS, Block.OAK_LEAVES, Block.SCAFFOLDING, Block.VINE, Block.MOSS_CARPET)
            .map { it.id() }

        private val collidableBlocksBlacklist =
            listOf<Block>(Block.OAK_LEAVES, Block.IRON_BARS, Block.OAK_FENCE).map { it.id() }
        private val collidableBlocksWhitelist = listOf<Block>().map { it.id() }
        val collidableBlocks = Block.values().filter {
            (it.isSolid && !collidableBlocksBlacklist.contains(it.id())) || collidableBlocksWhitelist.contains(it.id())
        }.map { it.id() }
    }

    //TODO: Replace with LazerTag game options?
    val killsToWin = 25

    val bossBarsMap = hashMapOf<Player, Pair<Player, BossBar>>()

    val respawnTasks = mutableListOf<MinestomRunnable>()
    val reloadTasks = hashMapOf<Player, MinestomRunnable>()

    override var spawnPosition = Pos(29.5, 15.5, -6.5)

    override fun playerJoin(player: Player) {
        player.gameMode = GameMode.SPECTATOR
        player.isInvisible = false
        player.setCanPickupItem(false)

        scoreboard?.createLine(
            Sidebar.ScoreboardLine(
                player.uuid.toString(),

                Component.text()
                    .append(Component.text(player.username, NamedTextColor.GRAY))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(0, NamedTextColor.GRAY, TextDecoration.BOLD))
                    .build(),

                0
            )
        )

    }

    override fun playerLeave(player: Player) {
        player.cleanup()
        scoreboard?.removeLine(player.uuid.toString())
    }

    override fun gameStarted() {
        scoreboard?.removeLine("InfoLine")
        gameState = GameState.PLAYING

        players.forEach(::respawn)
    }

    override fun playerDied(player: Player, killer: Entity?) {
        if (gameState == GameState.ENDING) return

        //player.inventory.clear()
        player.velocity = Vec(0.0, 0.0, 0.0)
        player.gameMode = GameMode.SPECTATOR
        player.isInvisible = true
        player.sendActionBar(Component.empty())
        player.setNoGravity(true)

        player.playSound(
            Sound.sound(SoundEvent.ENTITY_VILLAGER_DEATH, Sound.Source.MASTER, 1f, 1f),
            Sound.Emitter.self()
        )

        reloadTasks[player]?.cancel()
        reloadTasks.remove(player)

        if (killer != null && killer != player && killer is Player) {
            val kills = ++player.kills

            if (kills >= killsToWin) return victory(killer)

            killer.addEffect(Potion(PotionEffect.REGENERATION, 2, 6 * 20))

            scoreboard?.updateLineScore(killer.uuid.toString(), kills)
            scoreboard?.updateLineContent(
                killer.uuid.toString(),
                Component.text()
                    .append(Component.text(killer.username, NamedTextColor.GRAY))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(
                        Component.text(
                            kills,
                            TextColor.lerp(
                                kills.toFloat() / killsToWin.toFloat(),
                                NamedTextColor.GRAY,
                                NamedTextColor.LIGHT_PURPLE
                            ),
                            TextDecoration.BOLD
                        )
                    )
                    .build()
            )

            sendMessage(
                Component.text()
                    .append(Component.text("☠", NamedTextColor.RED))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(killer.username, NamedTextColor.WHITE))
                    .append(Component.text(" killed ", NamedTextColor.GRAY))
                    .append(Component.text(player.username, NamedTextColor.RED))
                    .append(Component.text(" with ", NamedTextColor.GRAY))
                    .append(Component.text(killer.heldGun?.name ?: "nothing apparently", NamedTextColor.GOLD))
            )

            killer.showTitle(
                Title.title(
                    Component.empty(),
                    Component.text()
                        .append(Component.text("☠ ", NamedTextColor.RED))
                        .append(Component.text(player.username, NamedTextColor.RED))
                        .build(),
                    Title.Times.of(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(500))
                )
            )

            killer.playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.PLAYER, 1f, 1f))

            player.showTitle(
                Title.title(
                    Component.text("YOU DIED", NamedTextColor.RED, TextDecoration.BOLD),
                    "<gray>Killed by <red><bold>${killer.username}</bold></red>".asMini(),
                    Title.Times.of(
                        Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)
                    )
                )
            )

            val coinItem = item(Material.SUNFLOWER)
            val random = ThreadLocalRandom.current()
            repeat(10) {
                val entity = Entity(EntityType.ITEM)
                val meta = entity.entityMeta as ItemEntityMeta
                meta.item = coinItem


                entity.velocity = Vec(
                    random.nextDouble(-1.0, 1.0) * 5,
                    random.nextDouble(0.5, 1.0) * 10,
                    random.nextDouble(-1.0, 1.0) * 5
                )

                entity.isAutoViewable = false
                entity.scheduleRemove(Duration.ofSeconds(3))
                entity.setInstance(instance, player.position.add(0.0, 0.5, 0.0))
                entity.addViewer(killer)
            }

            randomizeGun(killer)
        } else {
            sendMessage(
                Component.text()
                    .append(Component.text("☠", NamedTextColor.RED))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(player.username, NamedTextColor.RED))
                    .append(Component.text(" killed themselves", NamedTextColor.GRAY))
            )

            player.showTitle(
                Title.title(
                    Component.text("YOU DIED", NamedTextColor.RED, TextDecoration.BOLD),
                    "<rainbow>You killed yourself!".asMini(),
                    Title.Times.of(
                        Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)
                    )
                )
            )

            if (player.kills > 1) {
                val kills = --player.kills

                scoreboard?.updateLineScore(player.uuid.toString(), kills)
                scoreboard?.updateLineContent(
                    player.uuid.toString(),
                    Component.text()
                        .append(Component.text(player.username, NamedTextColor.GRAY))
                        .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(kills, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                        .build()
                )
            }
        }

        respawnTasks.add(object : MinestomRunnable(
            delay = Duration.ofSeconds(2),
            repeat = Duration.ofSeconds(1),
            iterations = 3,
            timer = timer
        ) {
            override fun run() {
                player.playSound(
                    Sound.sound(SoundEvent.BLOCK_WOODEN_BUTTON_CLICK_ON, Sound.Source.BLOCK, 1f, 1f),
                    Sound.Emitter.self()
                )
                player.showTitle(
                    Title.title(
                        Component.text(3 - currentIteration, NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.empty(),
                        Title.Times.of(
                            Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(200)
                        )
                    )
                )
            }

            override fun cancelled() {
                respawn(player)
            }
        })
    }

    override fun respawn(player: Player) = with(player) {
        player.spawnProtectionMillis = 3500
        teleport(getRandomRespawnPosition())
        reset()

        player.playSound(
            Sound.sound(SoundEvent.BLOCK_BEACON_ACTIVATE, Sound.Source.MASTER, 1f, 2f),
            Sound.Emitter.self()
        )

        if (gameState == GameState.ENDING) return

        // TODO: Replace with proper gun score system for other modes

        randomizeGun(player)
    }

    private fun victory(player: Player) {
        if (gameState == GameState.ENDING) return
        gameState = GameState.ENDING

        reloadTasks.values.forEach(MinestomRunnable::cancel)
        reloadTasks.clear()

        instance.entities
            .filter { it !is Player }
            .forEach(Entity::remove)

        val victoryTitle = Title.title(
            Component.text("VICTORY!", NamedTextColor.GOLD, TextDecoration.BOLD),
            Component.text(EndGameQuotes.victory.random(), NamedTextColor.GRAY),
            Title.Times.of(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(3))
        )
        val defeatTitle = Title.title(
            Component.text("DEFEAT!", NamedTextColor.RED, TextDecoration.BOLD),
            Component.text(EndGameQuotes.defeat.random(), NamedTextColor.GRAY),
            Title.Times.of(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(3))
        )

        players.forEach {
            it.inventory.clear()

            if (it == player) it.showTitle(victoryTitle)
            else it.showTitle(defeatTitle)

        }

        Manager.scheduler.buildTask { destroy() }
            .delay(5, TimeUnit.SECOND)
            .schedule()
    }

    override fun gameDestroyed() {
        respawnTasks.forEach(MinestomRunnable::cancel)
        respawnTasks.clear()
    }

    override fun registerEvents() = with(eventNode) {
        listenOnly<PlayerUseItemEvent> {
            isCancelled = true
            if (hand != Player.Hand.MAIN) return@listenOnly

            val heldGun = player.heldGun ?: return@listenOnly
            val lastShotMs = player.itemInMainHand.meta.getTag(lastShotTag) ?: return@listenOnly

            if (heldGun.shootMidReload) {
                val lastAmmo = player.itemInMainHand.meta.getTag(ammoTag)
                reloadTasks[player]?.cancel()
                reloadTasks.remove(player)

                player.setCooldown(player.itemInMainHand.material, 0, false)

                player.itemInMainHand = player.itemInMainHand.and {
                    removeTag(reloadingTag)
                    setTag(ammoTag, lastAmmo)
                }
            } else {
                if (player.itemInMainHand.meta.hasTag(reloadingTag)) return@listenOnly
            }


            if (lastShotMs > System.currentTimeMillis() - (heldGun.cooldown * 50)) {
                return@listenOnly
            }
            if (lastShotMs == 1L) {
                return@listenOnly
            }

            player.itemInMainHand = player.itemInMainHand.and {
                setTag(lastShotTag, System.currentTimeMillis())
            }

            object : MinestomRunnable(
                repeat = Duration.ofMillis((50 * heldGun.burstInterval).toLong()),
                iterations = heldGun.burstAmount,
                timer = timer
            ) {
                override fun run() {
                    if (!player.itemInMainHand.meta.hasTag(ammoTag)) {
                        cancel()
                        return
                    }
                    if (player.itemInMainHand.meta.getTag(ammoTag)!! <= 0) {
                        player.playSound(Sound.sound(SoundEvent.ENTITY_ITEM_BREAK, Sound.Source.PLAYER, 0.7f, 1.5f))
                        player.sendActionBar("<red>Press <bold><key:key.swapOffhand></bold> to reload!".asMini())

                        cancel()
                        return
                    }

                    playSound(heldGun.sound, player.position)

                    val damageMap = heldGun.shoot(player)

                    damageMap.forEach { (hitEntity, damage) ->
                        player.playSound(Sound.sound(SoundEvent.ENTITY_PLAYER_HURT, Sound.Source.PLAYER, 1f, 1f))

                        player.scheduleNextTick {
                            hitEntity.damage(DamageType.fromPlayer(player), damage)
                        }
                    }
                }
            }

        }

        listenOnly<PlayerTickEvent> {
            val activeRegenEffects = player.activeEffects.firstOrNull { it.potion.effect == PotionEffect.REGENERATION }
            if (activeRegenEffects != null && player.aliveTicks % (50 / (activeRegenEffects.potion.amplifier + 1)) == 0L) {
                player.health += 1f
            }
        }

        listenOnly<EntityTickEvent> {
            if (entity.entityType == EntityType.PLAYER) return@listenOnly

            if (entity.position.y <= 0 || entity.chunk == null) {
                entity.remove()
                return@listenOnly
            }

            if (!entity.hasTag(Gun.playerUUIDTag)) return@listenOnly


            val shooter: Player = Manager.connection.getPlayer(UUID.fromString(entity.getTag(Gun.playerUUIDTag)))!!
            val gun = Gun.registeredMap[entity.getTag(Gun.gunIdTag)!!] ?: return@listenOnly

            if (gun !is ProjectileGun) return@listenOnly

            // TODO: Better collisions

            if (entity.velocity.x == 0.0 || entity.velocity.y == 0.0 || entity.velocity.z == 0.0) {
                gun.collide(shooter, entity)
                return@listenOnly
            }
            if (entity.aliveTicks > 20 * 3) {
                gun.collide(shooter, entity)
                return@listenOnly
            }

            val intersectingPlayers = players
                .filter { entity.boundingBox.intersect(it.boundingBox) && it.gameMode == GameMode.ADVENTURE }
                .filter { if (entity.aliveTicks < 20) it != shooter else true }
            if (intersectingPlayers.isEmpty()) return@listenOnly

            gun.collideEntity(shooter, entity, intersectingPlayers)
        }

        cancel<PlayerBlockBreakEvent>()
        cancel<InventoryPreClickEvent>()
        cancel<PlayerBlockBreakEvent>()
        cancel<PlayerBlockPlaceEvent>()
        cancel<ItemDropEvent>()

        listenOnly<PlayerChangeHeldSlotEvent> {
            isCancelled = true
            player.setHeldItemSlot(4)
        }

        listenOnly<PlayerSwapItemEvent> {
            isCancelled = true

            val gun = Gun.registeredMap[offHandItem.getTag(Gun.gunIdTag)] ?: return@listenOnly

            if (player.itemInMainHand.meta.hasTag(reloadingTag) || player.itemInMainHand.meta.getTag(ammoTag) == gun.ammo
            ) {
                return@listenOnly
            }

            val ammoOnReload = player.itemInMainHand.getTag(ammoTag)!!
            val reloadTicks = gun.getReloadTicks(ammoOnReload)
            player.setCooldown(player.itemInMainHand.material, reloadTicks, true)

            val startingAmmo = if (gun.freshReload) 0f else ammoOnReload.toFloat()

            player.itemInMainHand = player.itemInMainHand.and {
                setTag(ammoTag, startingAmmo.toInt())
                setTag(reloadingTag, 1)
            }

            reloadTasks[player] = object : MinestomRunnable(
                repeat = Duration.ofMillis(50),
                iterations = reloadTicks,
                timer = timer
            ) {
                var currentAmmo = startingAmmo

                override fun run() {
                    val lastAmmo = currentAmmo
                    currentAmmo += (gun.ammo.toFloat() - startingAmmo) / reloadTicks.toFloat()

                    val lastAmmoRounded = floor(lastAmmo).toInt()
                    val roundedAmmo = floor(currentAmmo).toInt()

                    gun.renderAmmo(player, roundedAmmo, currentAmmo / gun.ammo.toFloat(), reloading = true)
                    if (roundedAmmo == lastAmmoRounded) return

                    player.itemInMainHand = player.itemInMainHand.and {
                        this.set(ammoTag, roundedAmmo)
                    }

                    player.playSound(
                        Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.PLAYER, 0.3f, 2f),
                        Sound.Emitter.self()
                    )
                }

                override fun cancelled() {
                    player.playSound(Sound.sound(SoundEvent.ENTITY_IRON_GOLEM_ATTACK, Sound.Source.PLAYER, 1f, 1f))
                    Manager.scheduler.buildTask {
                        player.playSound(
                            Sound.sound(
                                SoundEvent.ENTITY_IRON_GOLEM_ATTACK,
                                Sound.Source.PLAYER,
                                1f,
                                1f
                            ),
                            Sound.Emitter.self()
                        )
                    }.delay(Duration.ofMillis(50 * 3L)).schedule()

                    player.itemInMainHand = player.itemInMainHand.and {
                        set(ammoTag, gun.ammo)
                        removeTag(reloadingTag)
                    }

                    gun.renderAmmo(player, gun.ammo)
                }
            }
        }

        listenOnly<PlayerMoveEvent> {
            if (player.gameMode != GameMode.ADVENTURE) return@listenOnly

            if (newPosition.y() < 5) {
                kill(player, null)
            }
        }

        listenOnly<EntityDamageEvent> {
            if (damageType !is EntityDamage) {
                isCancelled = true
                return@listenOnly
            }

            val player: Player = entity as? Player ?: return@listenOnly
            if (player.hasSpawnProtection) {
                isCancelled = true
                return@listenOnly
            }

            val damager: Player = (damageType as EntityDamage).source as Player
            if (damager.hasSpawnProtection) damager.spawnProtectionMillis = null

            if (entity.health - damage <= 0) {
                isCancelled = true
                entity.health = 20f

                if (player != damager) {
                    bossBarsMap[damager]?.let { damager.hideBossBar(it.second) }
                }

                kill(player, damager)
                return@listenOnly
            }

            if (player == damager) {
                return@listenOnly
            }

            val bossBar: BossBar = if (bossBarsMap[damager] == null || bossBarsMap[damager]?.first != player) {
                bossBarsMap[damager]?.let { damager.hideBossBar(it.second) }
                val bb = BossBar.bossBar(
                    Component.text(player.username, NamedTextColor.GRAY),
                    1f,
                    BossBar.Color.RED,
                    BossBar.Overlay.NOTCHED_10
                )
                bossBarsMap[damager] = Pair(player, bb)
                bb
            } else {
                bossBarsMap[damager]!!.second
            }

            damager.showBossBar(bossBar)
            bossBar.progress((player.health - damage) / player.maxHealth)

            val rand = ThreadLocalRandom.current()
            val format = DecimalFormat("0.##")

            val healthEntity = Entity(EntityType.ARMOR_STAND)
            val healthEntityMeta = healthEntity.entityMeta as ArmorStandMeta

            healthEntity.isAutoViewable = false
            healthEntity.isInvisible = true
            healthEntity.customName =
                Component.text()
                    .append(Component.text("❤ "))
                    .append(Component.text(format.format(damage), NamedTextColor.WHITE, TextDecoration.BOLD))
                    .build()
                    .color(TextColor.lerp(damage / 20f, TextColor.color(255, 150, 150), NamedTextColor.DARK_RED))
            healthEntity.isCustomNameVisible = true
            healthEntity.setNoGravity(true)
            healthEntityMeta.setNotifyAboutChanges(false)
            healthEntityMeta.isMarker = true
            healthEntityMeta.isSmall = true
            healthEntityMeta.isHasNoBasePlate = true
            healthEntityMeta.setNotifyAboutChanges(true)

            healthEntity.setInstance(
                entity.instance!!,
                entity.position.sub(
                    rand.nextDouble(-0.5, 0.5),
                    rand.nextDouble(0.5) - 0.5,
                    rand.nextDouble(-0.5, 0.5)
                )
            )

            healthEntity.addViewer(damager)

            healthEntity.scheduleRemove(Duration.ofSeconds(2))

            object : MinestomRunnable(repeat = Duration.ofMillis(50), iterations = 50, timer = timer) {
                var accel = 0.5

                override fun run() {
                    healthEntity.teleport(healthEntity.position.add(0.0, accel, 0.0))
                    accel *= 0.60
                }
            }
        }
    }


    private fun randomizeGun(player: Player) {
        val gun = Gun.registeredMap.values.random()
        player.inventory.setItemStack(4, gun.item)
        gun.renderAmmo(player, gun.ammo)
        player.setHeldItemSlot(4)

        reloadTasks[player]?.cancel()
        reloadTasks.remove(player)
    }

    private fun getRandomRespawnPosition(): Pos {
        return LazerTagExtension.config.spawnPositions["dizzymc"]?.random() ?: Pos(0.5, 10.0, 0.5)
    }

    override fun instanceCreate(): Instance {
        val instance = Manager.instance.createInstanceContainer(
            //Manager.dimensionType.getDimension(NamespaceID.from("fullbright"))!!
        )
        instance.chunkLoader = AnvilLoader("./maps/lazertag/dizzymc/")

        return instance
    }

}