package com.redcoffee.puttputt.party;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Bridge to RCParties' {@code PartyService}, bound reflectively through the Bukkit services
 * manager.
 *
 * <p>Reflection rather than a compile-time import is a deliberate trade: RCParties is not published
 * to a Maven repository this build can resolve, and binding late means a version bump that renames
 * a method degrades to solo play (see {@link SoloPartyProvider}) instead of throwing
 * {@code NoSuchMethodError} on every stroke. {@link #bind} returns empty unless every method the
 * round logic needs is present, so there is no half-bound state.
 */
public final class RCPartiesProvider implements PartyProvider {

    private static final String SERVICE_CLASS = "com.redcoffee.parties.api.PartyService";

    private final Object service;
    private final Method getParty;
    private final Method partyId;
    private final Method leaderId;
    private final Method memberIds;
    private final Method acquireLock;
    private final Method releaseLock;
    private final Logger logger;

    private RCPartiesProvider(Object service, Method getParty, Method partyId, Method leaderId,
                              Method memberIds, Method acquireLock, Method releaseLock, Logger logger) {
        this.service = service;
        this.getParty = getParty;
        this.partyId = partyId;
        this.leaderId = leaderId;
        this.memberIds = memberIds;
        this.acquireLock = acquireLock;
        this.releaseLock = releaseLock;
        this.logger = logger;
    }

    /** Binds to a live RCParties, or returns empty if it is absent or has drifted. */
    public static Optional<PartyProvider> bind(Logger logger) {
        Plugin parties = Bukkit.getPluginManager().getPlugin("RCParties");
        if (parties == null || !parties.isEnabled()) {
            return Optional.empty();
        }
        try {
            Class<?> serviceClass = Class.forName(SERVICE_CLASS, false, parties.getClass().getClassLoader());
            Object service = Bukkit.getServicesManager().load(serviceClass);
            if (service == null) {
                logger.warning("RCParties is present but registered no PartyService; falling back to solo play.");
                return Optional.empty();
            }
            Method getParty = serviceClass.getMethod("getParty", UUID.class);
            Class<?> partyClass = getParty.getReturnType();
            // getParty may hand back Optional<Party>; unwrap that at call time.
            Class<?> resolved = partyClass == Optional.class ? Class.forName(
                    SERVICE_CLASS.replace("PartyService", "Party"), false, parties.getClass().getClassLoader())
                    : partyClass;
            return Optional.of(new RCPartiesProvider(
                    service,
                    getParty,
                    resolved.getMethod("getId"),
                    resolved.getMethod("getLeader"),
                    resolved.getMethod("getMembers"),
                    serviceClass.getMethod("acquireActivityLock", UUID.class, String.class),
                    serviceClass.getMethod("releaseActivityLock", UUID.class, String.class),
                    logger));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.log(Level.WARNING,
                    "RCParties is installed but its API does not match what RCPuttPutt expects; "
                            + "falling back to solo play.", ex);
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return "RCParties";
    }

    @Override
    public PartyView partyOf(Player player) {
        try {
            Object party = getParty.invoke(service, player.getUniqueId());
            if (party instanceof Optional<?> optional) {
                party = optional.orElse(null);
            }
            if (party == null) {
                return PartyView.solo(player.getUniqueId());
            }
            UUID id = (UUID) partyId.invoke(party);
            UUID leader = (UUID) leaderId.invoke(party);
            List<UUID> members = toUuidList(memberIds.invoke(party));
            if (members.isEmpty()) {
                members = List.of(leader);
            }
            return new PartyView(id, leader, members);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            logger.log(Level.WARNING, "RCParties lookup failed for " + player.getName()
                    + "; treating them as solo for this round.", ex);
            return PartyView.solo(player.getUniqueId());
        }
    }

    @Override
    public boolean acquireActivityLock(UUID partyId, String activityId) {
        try {
            return Boolean.TRUE.equals(acquireLock.invoke(service, partyId, activityId));
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.WARNING, "Could not take an RCParties activity lock for " + partyId, ex);
            return false;
        }
    }

    @Override
    public void releaseActivityLock(UUID partyId, String activityId) {
        try {
            releaseLock.invoke(service, partyId, activityId);
        } catch (ReflectiveOperationException ex) {
            // A leaked lock is bad, but throwing during round teardown would leak far more.
            logger.log(Level.WARNING, "Could not release the RCParties activity lock for " + partyId, ex);
        }
    }

    private static List<UUID> toUuidList(Object raw) {
        List<UUID> out = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element instanceof UUID uuid) {
                    out.add(uuid);
                } else if (element instanceof Player player) {
                    out.add(player.getUniqueId());
                }
            }
        }
        return out;
    }
}
