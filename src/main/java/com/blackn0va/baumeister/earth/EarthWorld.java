package com.blackn0va.baumeister.earth;

import org.bukkit.World;

/** Die Erdwelt, wie andere Teile des Plugins sie brauchen. */
public interface EarthWorld {

    /** Die geladene Erdwelt oder null (Datapack fehlt / noch kein Neustart). */
    World world();

    EarthProjection projection();

    HeightField ground();
}
