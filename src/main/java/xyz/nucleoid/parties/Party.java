package xyz.nucleoid.parties;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.MinecraftServer;
import xyz.nucleoid.plasmid.api.game.player.MutablePlayerSet;
import xyz.nucleoid.plasmid.api.util.PlayerRef;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Party {
    public static final int PARTY_CAPACITY = 4;

    private final UUID uuid;
    private PlayerRef owner;

    private final List<PlayerRef> members = new ObjectArrayList<>();
    private final Set<PlayerRef> pendingMembers = new ObjectOpenHashSet<>();
    private final MutablePlayerSet memberPlayers;


    Party(MinecraftServer server, PlayerRef owner) {
        this.memberPlayers = new MutablePlayerSet(server);
        this.add(owner);
        this.setOwner(owner);

        this.uuid = UUID.randomUUID();
    }

    PlayerRef getOwner() {
        return this.owner;
    }

    boolean setOwner(PlayerRef owner) {
        if (this.contains(owner)) {
            this.owner = owner;
            return true;
        }
        return false;
    }

    boolean isFull() {
        return this.members.size() >= PARTY_CAPACITY;
    }

    boolean invite(PlayerRef player) {
        if (this.memberPlayers.contains(player)) {
            return false;
        }
        return this.pendingMembers.add(player);
    }

    boolean acceptInvite(PlayerRef player) {
        if (this.isInvited(player)) {
            this.add(player);
            return true;
        }
        return false;
    }

    void add(PlayerRef player) {
        this.memberPlayers.add(player);
        this.members.add(player);
        this.pendingMembers.remove(player);
    }

    boolean remove(PlayerRef player) {
        if (this.memberPlayers.remove(player)) {
            this.members.remove(player);
            return true; // succeed to remove from members
        }
        return this.pendingMembers.remove(player); // if succeed to remove from pending
    }

    public boolean contains(PlayerRef player) {
        return this.memberPlayers.contains(player);
    }

    public boolean isInvited(PlayerRef player) {
        return this.pendingMembers.contains(player);
    }

    public boolean isOwner(PlayerRef from) {
        return from.equals(this.owner);
    }

    public List<PlayerRef> getMembers() {
        return this.members;
    }

    public MutablePlayerSet getMemberPlayers() {
        return this.memberPlayers;
    }

    public UUID getUuid() {
        return this.uuid;
    }
}
