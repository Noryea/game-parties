package xyz.nucleoid.parties;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.event.GameEvents;
import xyz.nucleoid.plasmid.api.game.GameSpaceManager;
import xyz.nucleoid.plasmid.api.util.PlayerRef;

import java.util.*;
import java.util.stream.Collectors;

public final class PartyManager {
    private static PartyManager instance;

    private final MinecraftServer server;
    private final Object2ObjectMap<PlayerRef, Party> playerToParty = new Object2ObjectOpenHashMap<>();

    private PartyManager(MinecraftServer server) {
        this.server = server;
    }

    @SuppressWarnings("UnstableApiUsage")
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var partyManager = PartyManager.get(server);
            partyManager.onPlayerJoin(handler.player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var partyManager = PartyManager.get(server);
            partyManager.onPlayerLogOut(handler.player);
        });

        GameEvents.COLLECT_PLAYERS_FOR_JOIN.register((gameSpace, player, additional) -> {
            var partyManager = PartyManager.get(player.getWorld().getServer());
            var gameSpaceManager = GameSpaceManager.get();

            var members = partyManager.getPartyMembers(player, true);

            for (var member : members) {
                if (!gameSpaceManager.inGame(member)) {
                    additional.add(member);
                }
            }
        });

        GameEvents.TEAM_SELECTION_LOBBY_FINALIZE.register((gameSpace, allocator, players) -> {
            var partyManager = PartyManager.get(gameSpace.getServer());

            var ungroupedPlayers = players.stream().collect(Collectors.toCollection(HashSet::new));

            for (ServerPlayerEntity player : players) {
                if (ungroupedPlayers.contains(player)) {
                    var members = partyManager.getPartyMembers(player, false);
                    System.out.println(player.getNameForScoreboard() + " members: " + members.stream().map(PlayerEntity::getNameForScoreboard).toList());
                    allocator.group(members);
                    ungroupedPlayers.removeAll(members);
                }
            }
        });
    }

    public static PartyManager get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new PartyManager(server);
        }
        return instance;
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        var ref = PlayerRef.of(player);

        for (var party : this.getAllParties()) {
            if (party.isInvited(ref)) {
                party.getOwner().ifOnline(this.server, owner -> {
                    var notification = PartyTexts.invitedReceiver(owner, party.getUuid());
                    player.sendMessage(notification.formatted(Formatting.GREEN));
                });
            }
        }
    }

    public void onPlayerLogOut(ServerPlayerEntity player) {
        var ref = PlayerRef.of(player);

        var party = this.playerToParty.remove(ref);
        if (party == null) {
            return;
        }
        party.getMemberPlayers().sendMessage(PartyTexts.leftGame(player));
        removePlayerFromParty(ref, party);
    }

    public PartyResult invitePlayer(PlayerRef owner, PlayerRef player) {
        var party = this.getOrCreateOwnParty(owner);
        if (party != null) {
            if (party.isFull()) {
                return PartyResult.err(PartyError.PARTY_FULL);
            }
            if (party.contains(player)) {
                return PartyResult.err(PartyError.ALREADY_JOINED);
            }
            return party.invite(player) ? PartyResult.ok(party) : PartyResult.err(PartyError.ALREADY_INVITED);
        }

        return PartyResult.err(PartyError.DOES_NOT_EXIST);
    }

    public PartyResult kickPlayer(PlayerRef owner, PlayerRef player) {
        if (owner.equals(player)) {
            return PartyResult.err(PartyError.CANNOT_REMOVE_SELF);
        }

        var party = this.getOwnParty(owner);
        if (party == null) {
            return PartyResult.err(PartyError.DOES_NOT_EXIST);
        }

        if (!party.contains(player)) {
            return PartyResult.err(PartyError.NOT_IN_PARTY);
        }

        removePlayerFromParty(player, party);
        return PartyResult.ok(party);
    }

    public PartyResult acceptInvite(PlayerRef player, @Nullable Party party) {
        if (this.playerToParty.containsKey(player)) {
            return PartyResult.err(PartyError.ALREADY_IN_A_PARTY);
        }

        if (party == null) {
            return PartyResult.err(PartyError.NOT_INVITED);
        }

        if (party.isFull()) {
            return PartyResult.err(PartyError.PARTY_FULL);
        }

        if (party.acceptInvite(player)) {
            this.playerToParty.put(player, party);
            return PartyResult.ok(party);
        }

        return PartyResult.err(PartyError.NOT_INVITED);
    }

    public PartyResult leaveParty(PlayerRef player) {
        var party = this.getParty(player);
        if (party == null) {
            return PartyResult.err(PartyError.NOT_IN_PARTY);
        }

        removePlayerFromParty(player, party);
        return PartyResult.ok(party);
    }

    public PartyResult transferParty(PlayerRef from, PlayerRef to) {
        var party = this.getOwnParty(from);
        if (party != null) {
            if (party.setOwner(to)) {
                return PartyResult.ok(party);
            }
            return PartyResult.err(PartyError.NOT_IN_PARTY);
        }

        return PartyResult.err(PartyError.DOES_NOT_EXIST);
    }

    public PartyResult disband(PlayerRef owner) {
        var party = this.getOwnParty(owner);
        if (party != null) {
            this.disbandParty(party);
            return PartyResult.ok(party);
        } else {
            return PartyResult.err(PartyError.DOES_NOT_EXIST);
        }
    }

    public PartyResult addPlayer(PlayerRef player, @Nullable Party party) {
        if (party == null) {
            return PartyResult.err(PartyError.DOES_NOT_EXIST);
        }

        var oldParty = this.getParty(player);
        if (party == oldParty) {
            return PartyResult.err(PartyError.ALREADY_JOINED);
        }

        if (party.isFull()) {
            return PartyResult.err(PartyError.PARTY_FULL);
        }

        if (oldParty != null) {
            this.removePlayerFromParty(player, oldParty);
        }
        party.add(player);
        this.playerToParty.put(player, party);

        return PartyResult.ok(party);
    }

    public PartyResult removePlayer(PlayerRef player) {
        var party = this.getParty(player);
        if (party == null) {
            return PartyResult.err(PartyError.NOT_IN_PARTY);
        }
        removePlayerFromParty(player, party);
        return PartyResult.ok(party);
    }

    private void removePlayerFromParty(PlayerRef ref, Party party) {
        var player = Objects.requireNonNull(ref.getEntity(this.server));
        boolean isOwner = party.isOwner(ref);
        if (party.remove(ref)) {
            this.playerToParty.remove(ref, party);
            if (isOwner) {
                this.onPartyOwnerLeave(player, party);
            }
        }
    }

    private void onPartyOwnerLeave(ServerPlayerEntity player, Party party) {
        var members = party.getMembers();

        if (!members.isEmpty()) {
            var nextMember = members.get(0);
            party.setOwner(nextMember);
            nextMember.ifOnline(
                this.server,
                nextPlayer -> nextPlayer.sendMessage(PartyTexts.transferredReceiver(player).formatted(Formatting.GOLD))
            );
        }
    }

    @Nullable
    public Party getParty(PlayerRef player) {
        return this.playerToParty.get(player);
    }

    @Nullable
    public Party getParty(UUID uuid) {
        for (Party party : this.playerToParty.values()) {
            if (party.getUuid().equals(uuid)) {
                return party;
            }
        }

        return null;
    }

    @Nullable
    public Party getOwnParty(PlayerRef owner) {
        var party = this.playerToParty.get(owner);
        if (party != null && party.isOwner(owner)) {
            return party;
        }
        return null;
    }

    @Nullable
    Party getOrCreateOwnParty(PlayerRef owner) {
        var party = this.playerToParty.computeIfAbsent(owner, this::createParty);
        if (party.isOwner(owner)) {
            return party;
        }
        return null;
    }

    private Party createParty(PlayerRef owner) {
        return new Party(this.server, owner);
    }

    private void disbandParty(Party party) {
        for (PlayerRef member : party.getMembers()) {
            this.playerToParty.remove(member, party);
        }
    }

    public Collection<ServerPlayerEntity> getPartyMembers(ServerPlayerEntity player, boolean own) {
        var ref = PlayerRef.of(player);
        var party = own ? this.getOwnParty(ref) : this.getParty(ref);

        if (party != null) {
            return Lists.newArrayList(party.getMemberPlayers());
        } else {
            return Collections.singleton(player);
        }
    }

    public Collection<Party> getAllParties() {
        return new HashSet<>(this.playerToParty.values());
    }
}
