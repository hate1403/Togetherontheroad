package io.togetherroad.core


data class Place(val id: String, val name: String, val latitude: Double, val longitude: Double)
data class Vehicle(val id: String, val origin: String, val etaMinutes: Int, val latitude: Double,
                   val longitude: Double, val source: String = "REPLAY")
data class Candidate(val place: Place, val arrivals: List<Int>, val detours: List<Int>) {
    val score: Double get() = arrivals.max() + .5 * detours.sum() + .5 * (arrivals.max() - arrivals.min())
}
enum class ApplyState { PENDING, APPROVED, APPLYING, APPLIED, FAILED, REJECTED }
data class Proposal(val id: Int, val place: Place, val expiresAt: Long,
                    val states: MutableMap<String, ApplyState>)

/** Deterministic offline fixture. No real positioning, routing or AI inference. */
class ReplayDrive {
    var active = false; private set
    var consent = false; private set
    var vehicles = emptyList<Vehicle>(); private set
    var proposal: Proposal? = null; private set
    private var nextId = 0
    val destination = Place("gangneung", "강릉", 37.7519, 128.8761)
    fun start() {
        active = true; consent = true; proposal = null
        vehicles = listOf(Vehicle("A", "서울", 150, 37.5665, 126.9780),
            Vehicle("B", "수원", 158, 37.2636, 127.0286), Vehicle("C", "인천", 155, 37.4563, 126.7052))
    }
    fun delayB() { check(active); vehicles = vehicles.map { if (it.id == "B") it.copy(etaMinutes = it.etaMinutes + 25) else it } }
    fun candidates(): List<Candidate> {
        check(active)
        val delay = vehicles.first { it.id == "B" }.etaMinutes - 158
        return listOf(
            Candidate(Place("demo-1", "합류 후보 1 · 가상 휴게소", 37.48, 127.75), listOf(40, 45 + delay, 43), listOf(4, 6, 5)),
            Candidate(Place("demo-2", "합류 후보 2 · 가상 주차장", 37.50, 128.05), listOf(58, 35 + delay, 56), listOf(6, 4, 6))
        ).sortedBy { it.score }
    }
    fun propose(now: Long): Proposal {
        check(active)
        return Proposal(++nextId, candidates().first().place, now + 120_000,
            vehicles.associate { it.id to ApplyState.PENDING }.toMutableMap()).also { proposal = it }
    }
    fun approve(vehicleId: String, proposalId: Int, now: Long) {
        check(active && consent)
        val p = requireNotNull(proposal)
        require(p.id == proposalId && now < p.expiresAt && vehicleId in p.states)
        require(p.states[vehicleId] == ApplyState.PENDING)
        p.states[vehicleId] = ApplyState.APPROVED
    }
    fun applying(vehicleId: String, proposalId: Int, now: Long) {
        val p = requireNotNull(proposal)
        check(active && p.id == proposalId && now < p.expiresAt)
        check(p.states[vehicleId] == ApplyState.APPROVED)
        p.states[vehicleId] = ApplyState.APPLYING
    }
    fun complete(vehicleId: String, proposalId: Int, success: Boolean) {
        val p = proposal ?: return
        if (!active || p.id != proposalId || p.states[vehicleId] != ApplyState.APPLYING) return
        p.states[vehicleId] = if (success) ApplyState.APPLIED else ApplyState.FAILED
    }
    fun stop() { active = false; consent = false; vehicles = emptyList(); proposal = null }
}
