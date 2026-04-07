package com.monkey.proudAuth.util;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class LocationSerializer {

    private LocationSerializer() {
    }

    public static Optional<Location> deserialize(Server server, ProudAuthSettings.AuthSpawn authSpawn) {
        World world = server.getWorld(authSpawn.world());
        if (world == null) {
            return Optional.empty();
        }

        return Optional.of(new Location(
                world,
                authSpawn.x(),
                authSpawn.y(),
                authSpawn.z(),
                authSpawn.yaw(),
                authSpawn.pitch()
        ));
    }

    public static Map<String, Object> serialize(Location location) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("world", location.getWorld() == null ? "world" : location.getWorld().getName());
        serialized.put("x", location.getX());
        serialized.put("y", location.getY());
        serialized.put("z", location.getZ());
        serialized.put("yaw", location.getYaw());
        serialized.put("pitch", location.getPitch());
        return serialized;
    }
}
