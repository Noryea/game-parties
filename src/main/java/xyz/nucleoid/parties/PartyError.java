package xyz.nucleoid.parties;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

public enum PartyError {
    DOES_NOT_EXIST,
    ALREADY_INVITED,
    ALREADY_JOINED,
    ALREADY_IN_A_PARTY,
    CANNOT_REMOVE_SELF,
    NOT_IN_PARTY,
    NOT_IN_ANY_PARTY,
    NOT_INVITED,
    PARTY_FULL;

    public static MutableText errorText(PartyError error, String playerName) {
        return switch (error) {
            case DOES_NOT_EXIST -> Text.translatable("text.game_parties.party.error.does_not_exist");
            case ALREADY_INVITED -> Text.translatable("text.game_parties.party.error.already_invited", playerName);
            case ALREADY_JOINED -> Text.translatable("text.game_parties.party.error.already_in_party");
            case ALREADY_IN_A_PARTY -> Text.translatable("text.game_parties.party.error.already_in_your_party");
            case CANNOT_REMOVE_SELF -> Text.translatable("text.game_parties.party.error.cannot_remove_self");
            case NOT_IN_PARTY -> Text.translatable("text.game_parties.party.error.not_in_party", playerName);
            case NOT_IN_ANY_PARTY -> Text.translatable("text.game_parties.party.error.not_in_any_party");
            case NOT_INVITED -> Text.translatable("text.game_parties.party.error.not_invited");
            case PARTY_FULL -> Text.translatable("text.game_parties.party.error.party_full", Party.PARTY_CAPACITY);
        };
    }
}
