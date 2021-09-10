package emortal.lazertag.utils

import emortal.immortal.particle.ParticleUtils
import emortal.immortal.particle.shapes.ParticleLine
import emortal.immortal.particle.shapes.ParticlePoly
import emortal.immortal.particle.shapes.ParticleSingle
import net.minestom.server.coordinate.Vec

class PolygonBuilder(val particleSingle: ParticleSingle) {

    val lines = mutableSetOf<ParticleLine>()
    var currentVec = Vec.ZERO

    fun append(line: ParticleLine): PolygonBuilder {
        lines.add(line)

        return this
    }

    fun jumpTo(vec: Vec): PolygonBuilder {
        currentVec = vec

        return this
    }

    fun lineTo(vec: Vec): PolygonBuilder {
        lines.add(ParticleUtils.line(particleSingle, currentVec, vec))
        jumpTo(vec)

        return this
    }

    fun build(): ParticlePoly {
        return ParticlePoly(lines)
    }

}