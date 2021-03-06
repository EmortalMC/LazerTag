package dev.emortal.lazertag.config

import kotlinx.serialization.Serializable
import net.minestom.server.coordinate.Pos
import world.cepi.kstom.serializer.PositionSerializer

@Serializable
class LazerTagConfig(
    var spawnPositions: HashMap<String, Array<@Serializable(with = PositionSerializer::class) Pos>> = hashMapOf()

)