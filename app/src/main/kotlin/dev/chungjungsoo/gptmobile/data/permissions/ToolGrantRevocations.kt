package dev.chungjungsoo.gptmobile.data.permissions

/** A memory-only denial precedes queued disk work; a failed commit never reopens it. */
internal class ToolGrantRevocations {
    data class Ticket(val scope: String, val generation: Long)
    data class Snapshot(val generations: Map<String, Long>)
    private data class State(val generation: Long, val pending: Boolean)
    private val states = mutableMapOf<String, State>()
    private var sequence = 0L

    @Synchronized fun begin(scope: String): Ticket {
        val ticket = Ticket(scope, ++sequence)
        states[scope] = State(ticket.generation, true)
        return ticket
    }

    @Synchronized fun complete(ticket: Ticket) {
        if (states[ticket.scope]?.generation == ticket.generation) states[ticket.scope] = State(ticket.generation, false)
    }

    @Synchronized fun snapshot(scopes: Collection<String>): Snapshot? {
        if (scopes.any { states[it]?.pending == true }) return null
        return Snapshot(scopes.associateWith { states[it]?.generation ?: 0L })
    }

    @Synchronized fun current(snapshot: Snapshot): Boolean = snapshot.generations.all { (scope, generation) ->
        states[scope]?.let { !it.pending && it.generation == generation } ?: (generation == 0L)
    }

    @Synchronized fun revision(): Long = sequence
}
