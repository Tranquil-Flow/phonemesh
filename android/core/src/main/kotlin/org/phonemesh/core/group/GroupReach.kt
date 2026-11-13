package org.phonemesh.core.group

/**
 * Which path an admin uses to reach each member of the next list with the new epoch's key (v0.1.3 G3c; relayed group
 * control design, "Which path an admin uses"; accounts decisions 79 and 80).
 *
 * For each other member of the next list:
 * 1. a contact → the private `GroupKey` as today (a forward-secret pairwise session; this works with a v0.1.2 member);
 * 2. otherwise, a box key announced on the list held → an envelope (op 8) sealed to that key;
 * 3. otherwise → the change does not start, and the admin is told who is missing, as `CannotReach` does now.
 *
 * Nothing is sent to anyone when a member is missing: a change that reaches some members and not others would leave
 * the group half in one epoch and half in another. The admin's own key is not part of this: it holds it itself.
 *
 * A member of the next list that is not on the list held announced nothing there, so it has no key to be reached by;
 * only the members of the list held can be reached by an envelope.
 */
object GroupReach {
    /** How one member of the next list is reached. */
    enum class Path {
        /** Over the private session, as today. */
        PRIVATE,

        /** In an envelope sealed to the box key the member announced on the list held. */
        ENVELOPE,

        /** Not at all: the change does not start. */
        NONE,
    }

    /** What the admin does for each member of the next list. */
    class Plan internal constructor(
        private val paths: Map<List<Byte>, Path>,
        private val boxes: Map<List<Byte>, ByteArray>,
    ) {
        /** Whether the change may start: every member of the next list can be reached. */
        val canStart: Boolean get() = paths.values.none { it == Path.NONE }

        /** The members of the next list, and how each is reached. */
        val choices: List<ByteArray> get() = paths.keys.map { it.toByteArray() }

        /** The members the admin cannot reach: the change does not start, and these are named. */
        val unreachable: List<ByteArray> get() = paths.filterValues { it == Path.NONE }.keys.map { it.toByteArray() }

        /** How [identity] is reached, or null when it is not a member of the next list. */
        fun pathOf(identity: ByteArray): Path? = paths[identity.toList()]

        /** The box key an envelope to [identity] is sealed to, or null when there is none. */
        fun boxKeyFor(identity: ByteArray): ByteArray? = boxes[identity.toList()]?.copyOf()
    }

    /**
     * The plan for the epoch that [next] starts, from the list [held] (null when there is none) and the box keys
     * [keys] holds. [me] is this phone, the admin: it holds its own key. [isContact] says whether a member has a
     * private session with this phone.
     */
    fun plan(
        next: GroupRoster, held: GroupRoster?, me: ByteArray,
        isContact: (ByteArray) -> Boolean, keys: GroupBoxKeys,
    ): Plan {
        val paths = LinkedHashMap<List<Byte>, Path>()
        val boxes = LinkedHashMap<List<Byte>, ByteArray>()
        for (m in next.members) {
            if (m.identity.contentEquals(me)) continue
            val id = m.identity
            when {
                isContact(id) -> paths[id.toList()] = Path.PRIVATE
                else -> {
                    // Only a member of the list held announced a key there, so only it can be reached by an envelope.
                    val box = if (held?.member(id) != null) keys.keyOf(id) else null
                    if (box == null) paths[id.toList()] = Path.NONE
                    else {
                        paths[id.toList()] = Path.ENVELOPE
                        boxes[id.toList()] = box
                    }
                }
            }
        }
        return Plan(paths, boxes)
    }
}
