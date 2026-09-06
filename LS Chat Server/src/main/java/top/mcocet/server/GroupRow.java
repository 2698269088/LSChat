package top.mcocet.server;

/** 群组记录。joinPolicy: approval(需审核) / open(直接加入)。 */
public record GroupRow(long id, String name, long ownerId, String joinPolicy, boolean mutedAll) {
}
