package io.github.ahmetcopur.tournament.tournament;

import java.time.Instant;

/** A group member's scoring state as a plain value (not a managed entity), for paging and Redis. */
public record MemberScore(long id, long groupId, long userId, String country, int score, Instant scoreUpdatedAt) {

    public static MemberScore of(GroupMember m) {
        return new MemberScore(m.getId(), m.getGroupId(), m.getUserId(), m.getCountry(), m.getScore(),
                m.getScoreUpdatedAt());
    }
}
