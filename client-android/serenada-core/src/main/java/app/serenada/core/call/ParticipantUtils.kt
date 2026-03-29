package app.serenada.core.call

/**
 * Deduplicates a list of participants by CID, preserving insertion order.
 * If [localPeerId] is not already in the list, a synthetic entry is appended.
 */
internal fun dedupeParticipants(
    participants: List<Participant>,
    localPeerId: String?,
): List<Participant> {
    val deduped = linkedMapOf<String, Participant>()
    participants.forEach { participant ->
        if (participant.cid.isNotBlank()) {
            deduped[participant.cid] = participant
        }
    }
    if (!localPeerId.isNullOrBlank() && !deduped.containsKey(localPeerId)) {
        deduped[localPeerId] = Participant(cid = localPeerId, joinedAt = null)
    }
    return deduped.values.toList()
}

/**
 * Resolves the host peer ID from a priority chain:
 * explicit provider value → current session host → local peer → first participant.
 * Returns `null` only when [participants] is empty and all candidates are null.
 */
internal fun resolveHostPeerId(
    explicitHostPeerId: String?,
    participants: List<Participant>,
    currentHostPeerId: String?,
    localPeerId: String?,
): String? {
    val participantIds = participants.map { it.cid }.toSet()
    return sequenceOf(explicitHostPeerId, currentHostPeerId, localPeerId)
        .filterNotNull()
        .firstOrNull { participantIds.contains(it) }
        ?: participants.firstOrNull()?.cid
}
