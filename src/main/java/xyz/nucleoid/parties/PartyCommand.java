package xyz.nucleoid.parties;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import xyz.nucleoid.plasmid.api.game.player.PlayerSet;
import xyz.nucleoid.plasmid.api.util.PlayerRef;

import java.util.ArrayList;
import java.util.Comparator;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class PartyCommand {
    // @formatter:off
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("party")
                .then(literal("list_all")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(PartyCommand::listParties)
                )
                .then(literal("list").executes(PartyCommand::listPlayerParty))
                .then(literal("invite")
                    .then(argument("player", EntityArgumentType.player())
                    .executes(PartyCommand::invitePlayer)
                ))
                .then(literal("kick")
                    .then(argument("player", GameProfileArgumentType.gameProfile())
                    .executes(PartyCommand::kickPlayer)
                ))
                .then(literal("transfer")
                    .then(argument("player", EntityArgumentType.player())
                    .executes(PartyCommand::transferToPlayer)
                ))
                .then(literal("accept")
                    .then(argument("owner", EntityArgumentType.player())
                        .executes(PartyCommand::acceptInviteByOwner)
                    )
                    .then(argument("party", UuidArgumentType.uuid())
                        .executes(PartyCommand::acceptInviteByUuid)
                    )
                )
                .then(literal("leave").executes(PartyCommand::leave))
                .then(literal("disband").executes(PartyCommand::disband))
                .then(literal("add")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("player", EntityArgumentType.player())
                        .then(argument("owner", EntityArgumentType.player())
                            .executes(PartyCommand::addPlayerByPlayer)
                        )
                        .then(argument("party", UuidArgumentType.uuid())
                            .executes(PartyCommand::addPlayerByUuid)
                        )
                    )
                )
                .then(literal("remove")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("player", EntityArgumentType.player())
                    .executes(PartyCommand::removePlayer)
                ))
        );
    }
    // @formatter:on

    private static int listParties(CommandContext<ServerCommandSource> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();

        var partyManager = PartyManager.get(server);
        var parties = new ArrayList<>(partyManager.getAllParties());

        if (parties.isEmpty()) {
            source.sendError(PartyTexts.noParties());
            return 0;
        }

        parties.sort(Comparator.comparing(Party::getUuid));

        source.sendFeedback(() -> {
            boolean first = true;
            var text = Text.empty();
            for (var party : parties) {
                if (first) {
                    first = false;
                } else {
                    text.append(ScreenTexts.LINE_BREAK);
                }
                text.append(getPartyList(server, party));
            }
            text.append(ScreenTexts.LINE_BREAK);

            return text;
        }, false);

        return parties.size();
    }

    private static int listPlayerParty(CommandContext<ServerCommandSource> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        var partyManager = PartyManager.get(server);
        var player = source.getPlayer();

        var party = partyManager.getParty(PlayerRef.of(player));
        if (party == null) {
            source.sendError(PartyError.errorText(PartyError.NOT_IN_ANY_PARTY, player.getGameProfile().getName()));
            return 0;
        }

        source.sendFeedback(() -> getPartyList(server, party).append(ScreenTexts.LINE_BREAK), false);

        return party.getMemberPlayers().size();
    }

    private static MutableText getPartyList(MinecraftServer server, Party party) {
        var text = Text.empty();
        text.append(PartyTexts.listEntry(party.getUuid()));

        var members = new ArrayList<>(party.getMembers());
        members.sort(Comparator.comparing(PlayerRef::id));

        for (var member : members) {
            text.append(ScreenTexts.LINE_BREAK);

            if (party.isOwner(member)) {
                text.append(PartyTexts.listMemberEntryType(member, server, PartyTexts.listMemberTypeOwner().formatted(Formatting.LIGHT_PURPLE)));
            } else if (party.contains(member)) {
                text.append(PartyTexts.listMemberEntry(member, server));
            } else {
                text.append(PartyTexts.listMemberEntryType(member, server, PartyTexts.listMemberTypePending().formatted(Formatting.GRAY)));
            }
        }

        return text;
    }

    private static int invitePlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        var owner = source.getPlayer();

        var player = EntityArgumentType.getPlayer(ctx, "player");

        var partyManager = PartyManager.get(source.getServer());
        var result = partyManager.invitePlayer(PlayerRef.of(owner), PlayerRef.of(player));
        if (result.isOk()) {
            source.sendFeedback(() -> PartyTexts.invitedSender(player).formatted(Formatting.GREEN), false);
            var notification = PartyTexts.invitedReceiver(owner, result.party().getUuid()).formatted(Formatting.GOLD);
            player.sendMessage(notification.formatted(Formatting.GREEN));
        } else {
            var error = result.error();
            source.sendError(PartyTexts.displayError(error, player));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int kickPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        var server = source.getServer();
        var owner = source.getPlayer();

        var profiles = GameProfileArgumentType.getProfileArgument(ctx, "player");

        for (var profile : profiles) {
            var partyManager = PartyManager.get(source.getServer());
            var result = partyManager.kickPlayer(PlayerRef.of(owner), PlayerRef.of(profile));
            if (result.isOk()) {
                Party party = result.party();
                PlayerSet memberPlayers = party.getMemberPlayers();
                PlayerRef.of(profile).ifOnline(server, player -> {
                    var message = PartyTexts.kickedSender(player);
                    memberPlayers.sendMessage(message);
                    player.sendMessage(PartyTexts.kickedReceiver().formatted(Formatting.RED), false);
                });
            } else {
                var error = result.error();
                source.sendError(PartyTexts.displayError(error, profile.getName()));
                return 0;
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int transferToPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        var oldOwner = source.getPlayer();
        var newOwner = EntityArgumentType.getPlayer(ctx, "player");

        var partyManager = PartyManager.get(source.getServer());
        var result = partyManager.transferParty(PlayerRef.of(oldOwner), PlayerRef.of(newOwner));
        if (result.isOk()) {
            if (newOwner.getUuid() != oldOwner.getUuid()) {
                source.sendFeedback(
                    () -> PartyTexts.transferredSender(newOwner).formatted(Formatting.GOLD),
                    false
                );
            }

            newOwner.sendMessage(
                    PartyTexts.transferredReceiver(oldOwner).formatted(Formatting.GOLD),
                    false
            );
        } else {
            var error = result.error();
            source.sendError(PartyTexts.displayError(error, newOwner));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int acceptInviteByOwner(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var owner = EntityArgumentType.getPlayer(ctx, "owner");
        var partyManager = PartyManager.get(ctx.getSource().getServer());

        return acceptInvite(ctx, partyManager.getOwnParty(PlayerRef.of(owner)));
    }

    private static int acceptInviteByUuid(CommandContext<ServerCommandSource> ctx) {
        var uuid = UuidArgumentType.getUuid(ctx, "party");
        var partyManager = PartyManager.get(ctx.getSource().getServer());

        return acceptInvite(ctx, partyManager.getParty(uuid));
    }

    private static int acceptInvite(CommandContext<ServerCommandSource> ctx, Party party) {
        var source = ctx.getSource();
        var player = source.getPlayer();

        var partyManager = PartyManager.get(source.getServer());
        var result = partyManager.acceptInvite(PlayerRef.of(player), party);
        if (result.isOk()) {
            party.getMemberPlayers().sendMessage(PartyTexts.joinSuccess(player));
        } else {
            var error = result.error();
            source.sendError(PartyTexts.displayError(error, player));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int leave(CommandContext<ServerCommandSource> ctx) {
        var source = ctx.getSource();
        var player = source.getPlayer();

        var partyManager = PartyManager.get(source.getServer());
        var result = partyManager.leaveParty(PlayerRef.of(player));
        if (result.isOk()) {
            var party = result.party();
            var message = PartyTexts.leaveSuccess(player);
            party.getMemberPlayers().sendMessage(message);
            player.sendMessage(message);
        } else {
            var error = result.error();
            source.sendError(PartyTexts.displayError(error, player));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int disband(CommandContext<ServerCommandSource> ctx) {
        var source = ctx.getSource();
        var owner = source.getPlayer();

        var partyManager = PartyManager.get(source.getServer());
        var result = partyManager.disband(PlayerRef.of(owner));
        if (result.isOk()) {
            var party = result.party();
            party.getMemberPlayers().sendMessage(PartyTexts.disbandSuccess());
        } else {
            var error = result.error();
            source.sendError(PartyTexts.displayError(error, owner));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int addPlayerByPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var owner = EntityArgumentType.getPlayer(ctx, "owner");
        var partyManager = PartyManager.get(ctx.getSource().getServer());
        var party = partyManager.getParty(PlayerRef.of(owner));
        if (party == null) {
            party = partyManager.getOrCreateOwnParty(PlayerRef.of(owner));
        }
        return addPlayer(ctx, party);
    }

    private static int addPlayerByUuid(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var uuid = UuidArgumentType.getUuid(ctx, "party");
        var partyManager = PartyManager.get(ctx.getSource().getServer());

        return addPlayer(ctx, partyManager.getParty(uuid));
    }

    private static int addPlayer(CommandContext<ServerCommandSource> ctx, Party party) throws CommandSyntaxException {
        var source = ctx.getSource();
        var player = EntityArgumentType.getPlayer(ctx, "player");

        var partyManager = PartyManager.get(source.getServer());
        var result = partyManager.addPlayer(PlayerRef.of(player), party);
        if (result.isOk()) {
            party.getMemberPlayers().sendMessage(PartyTexts.addSuccess(player));
        } else {
            var error = result.error();
            source.sendError(PartyTexts.displayError(error, player));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int removePlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        var player = EntityArgumentType.getPlayer(ctx, "player");

        var partyManager = PartyManager.get(source.getServer());
        var result = partyManager.removePlayer(PlayerRef.of(player));
        if (result.isOk()) {
            var message = PartyTexts.removeSuccess(player);
            result.party().getMemberPlayers().sendMessage(message);
            player.sendMessage(message);
        } else {
            var error = result.error();
            source.sendError(PartyTexts.displayError(error, player));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }
}
