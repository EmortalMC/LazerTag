package emortal.lazertag

import emortal.lazertag.commands.NewInstanceCommand
import emortal.lazertag.commands.SaveInstanceCommand
import emortal.lazertag.maps.MapManager
import net.minestom.server.extensions.Extension
import world.cepi.kstom.command.register
import world.cepi.kstom.command.unregister

class LazerTagExtension : Extension() {

    override fun initialize() {

        EventListener.init(this)
        MapManager.init()

        SaveInstanceCommand.register()
        NewInstanceCommand.register()

        logger.info("[LazerTagExtension] has been enabled!")
    }

    override fun terminate() {

        SaveInstanceCommand.unregister()
        NewInstanceCommand.unregister()

        logger.info("[LazerTagExtension] has been disabled!")
    }

}