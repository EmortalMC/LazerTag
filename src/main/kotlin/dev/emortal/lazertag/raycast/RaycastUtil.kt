package dev.emortal.lazertag.raycast

import dev.emortal.lazertag.game.LazerTagGame
import dev.emortal.lazertag.utils.breakBlock
import dev.emortal.rayfast.area.area3d.Area3d
import dev.emortal.rayfast.area.area3d.Area3dRectangularPrism
import dev.emortal.rayfast.grid.GridCast
import net.minestom.server.collision.BoundingBox
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import world.cepi.kstom.util.component1
import world.cepi.kstom.util.component2
import world.cepi.kstom.util.component3

/**
 * Class to make Rayfast easier to use with Minestom
 *
 *
 */
object RaycastUtil {

    private val boundingBoxToArea3dMap = HashMap<BoundingBox, Area3d>()

    init {
        Area3d.CONVERTER.register(BoundingBox::class.java) { box ->
            boundingBoxToArea3dMap.computeIfAbsent(box) { it ->
                Area3dRectangularPrism.wrapper(
                    it,
                    { it.minX }, { it.minY }, { it.minZ },
                    { it.maxX }, { it.maxY }, { it.maxZ }
                )
            }
            boundingBoxToArea3dMap[box]
        }
    }

    val Entity.area3d: Area3d
        get() = Area3d.CONVERTER.from(boundingBox)

    fun Entity.fastHasLineOfSight(entity: Entity): Boolean {
        val (x, y, z) = this

        val direction = this.position.asVec().sub(entity.position.asVec()).normalize()

        return this.area3d.lineIntersects(
            x, y, z,
            direction.x(), direction.y(), direction.z()
        )
    }

    fun raycastBlock(instance: Instance, startPoint: Point, direction: Vec, maxDistance: Double): Pos? {
        val gridIterator = GridCast.createExactGridIterator(
            startPoint.x(), startPoint.y(), startPoint.z(),
            direction.x(), direction.y(), direction.z(),
            1.0, maxDistance
        )

        while (gridIterator.hasNext()) {
            val gridUnit = gridIterator.next()
            val pos = Pos(gridUnit[0], gridUnit[1], gridUnit[2])
            val hitBlock = instance.getBlock(pos)

            if (LazerTagGame.destructableBlocks.contains(hitBlock.id())) {
                instance.setBlock(pos, Block.AIR)
                instance.breakBlock(pos, hitBlock)
            }

            if (LazerTagGame.collidableBlocks.contains(hitBlock.id())) {
                return pos
            }
        }

        return null
    }

    fun raycastEntity(
        instance: Instance,
        startPoint: Point,
        direction: Vec,
        maxDistance: Double,
        hitFilter: (Entity) -> Boolean = { true }
    ): Pair<Entity, Pos>? {
        instance.entities
            .filter { it.getDistance(startPoint) <= maxDistance }
            .filter { hitFilter.invoke(it) }
            .forEach {
                val area = it.area3d
                val intersection = area.lineIntersection(
                    startPoint.x(), startPoint.y(), startPoint.z(),
                    direction.x(), direction.y(), direction.z()
                )
                if (intersection != null) {
                    return Pair(it, Pos(intersection[0], intersection[1], intersection[2]))
                }
            }

        return null
    }

    fun raycast(
        instance: Instance,
        startPoint: Point,
        direction: Vec,
        maxDistance: Double,
        hitFilter: (Entity) -> Boolean = { true }
    ): RaycastResult {
        val blockRaycast = raycastBlock(instance, startPoint, direction, maxDistance)
        val entityRaycast = raycastEntity(instance, startPoint, direction, maxDistance, hitFilter)

        if (entityRaycast == null && blockRaycast == null) {
            return RaycastResult(RaycastResultType.HIT_NOTHING, null, null)
        }

        if (entityRaycast == null && blockRaycast != null) {
            return RaycastResult(RaycastResultType.HIT_BLOCK, null, blockRaycast)
        }

        if (entityRaycast != null && blockRaycast == null) {
            return RaycastResult(RaycastResultType.HIT_ENTITY, entityRaycast.first, entityRaycast.second)
        }

        // Both entity and block check have collided, time to see which is closer!

        val distanceFromEntity = startPoint.distance(entityRaycast!!.second)
        val distanceFromBlock = startPoint.distance(blockRaycast!!)

        return if (distanceFromBlock > distanceFromEntity) {
            RaycastResult(RaycastResultType.HIT_ENTITY, entityRaycast.first, entityRaycast.second)
        } else {
            RaycastResult(RaycastResultType.HIT_BLOCK, null, blockRaycast)
        }

    }

}