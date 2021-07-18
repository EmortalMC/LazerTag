package emortal.gungame.gun

import emortal.gungame.utils.MathUtils
import emortal.gungame.utils.ParticleUtils
import io.github.bloepiloepi.particles.shapes.ParticleShape
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.color.Color
import net.minestom.server.entity.Entity
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemMetaBuilder
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.sound.SoundEvent
import net.minestom.server.tag.Tag
import net.minestom.server.utils.Position
import net.minestom.server.utils.Vector
import world.cepi.kstom.raycast.HitType
import world.cepi.kstom.raycast.RayCast
import world.cepi.kstom.util.eyePosition
import world.cepi.kstom.util.spread

sealed class Gun(val name: String, val id: Int) {

    companion object {
        val registeredMap: Map<Int, Gun>
            get() = Gun::class.sealedSubclasses.map { it.objectInstance }.filterNotNull().associateBy { it.id }
    }

    open val material: Material = Material.WOODEN_HOE
    open val color: TextColor = NamedTextColor.WHITE

    val item by lazy {
        ItemStack.builder(material)
            .displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))
            .meta { meta: ItemMetaBuilder ->
                meta.set(Tag.Long("lastShot"), 0)
                meta.set(Tag.Byte("reloading"), 0)
                meta.set(Tag.Integer("ammo"), ammo)
                meta.customModelData(id)
            }.build()
    }

    open val damage: Float = 1f // PER BULLET!
    open val numberOfBullets: Int = 1
    open val spread: Double = 0.0
    open val cooldown: Long = 1L // In millis
    open val ammo: Int = 10
    open val reloadTime: Long = 2000L // In millis
    open val maxDistance: Double = 10.0

    open val burstAmount: Int = 1
    open val burstInterval: Long = 0 // In ticks

    open val sound: Sound = Sound.sound(SoundEvent.BLAZE_HURT, Sound.Source.PLAYER, 1f, 1f)

    open fun shoot(player: Player): HashMap<Player, Float> {
        val damageMap = HashMap<Player, Float>()

        val instance = player.instance!!
        val eyePos = player.eyePosition()
        val eyeDir = eyePos.direction

        repeat(numberOfBullets) {

            val direction = eyeDir.spread(spread).normalize()

            val raycast = RayCast.castRay(
                instance,
                player,
                eyePos.toVector(),
                direction,
                maxDistance,
                0.5,
                acceptEntity = { _: Vector, entity: Entity ->
                    entity is Player && entity.gameMode == GameMode.ADVENTURE /*&& entity.team != player.team*/
                }, // Accept if entity is a player and is in adventure mode (prevents spectators blocking bullets) and is not on the same team
                margin = 0.3
            )
            val lastPos = raycast.finalPosition.toPosition()

            if (raycast.hitType == HitType.ENTITY) {
                val hitPlayer: Player = raycast.hitEntity!! as Player

                val shapeOptions = ParticleUtils.getColouredShapeOptions(Color(255, 0, 0), Color(20, 20, 20), 1.5f)
                ParticleShape.line(raycast.finalPosition.subtract(direction.multiply(6)).toPosition(), lastPos)
                    .iterator(shapeOptions).draw(instance, Position(0.0, 0.0, 0.0))

                damageMap[hitPlayer] = damageMap.getOrDefault(hitPlayer, 0f) + damage
            } else {
                val shapeOptions = ParticleUtils.getColouredShapeOptions(Color(100, 100, 100), Color(50, 50, 50), 0.2f)
                ParticleShape.line(eyePos, lastPos)
                    .iterator(shapeOptions).draw(instance, Position(0.0, 0.0, 0.0))
            }

        }

        shootAfter(player)

        return damageMap
    }

    open fun shootAfter(player: Player) {

    }

    open fun collide(player: Player, projectile: Entity) {

    }

    fun renderAmmo(player: Player, currentAmmo: Int) {
        val blocks = 40
        val ammoPercentage: Float = currentAmmo.toFloat() / ammo.toFloat()
        val completedBlocks: Int = (ammoPercentage * blocks).toInt()
        val incompleteBlocks: Int = blocks - completedBlocks

        player.sendActionBar(
            Component.text()
                .append(Component.text("|".repeat(completedBlocks), NamedTextColor.GOLD))
                .append(Component.text("|".repeat(incompleteBlocks), NamedTextColor.DARK_GRAY))
                .append(Component.text(" ${String.format("%0${MathUtils.digitsInNumber(ammo)}d", currentAmmo)}/$ammo", NamedTextColor.DARK_GRAY))
                .build()
        )
    }
}