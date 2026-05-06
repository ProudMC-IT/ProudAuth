package com.monkey.proudAuth.velocity.monitor;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;

public final class VelocityMonitorListener {

    private final VelocityMonitorService monitorService;

    public VelocityMonitorListener(VelocityMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        monitorService.sendPlayerJoin(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        monitorService.sendPlayerQuit(event.getPlayer());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        monitorService.sendPlayerMove(event.getPlayer());
    }
}