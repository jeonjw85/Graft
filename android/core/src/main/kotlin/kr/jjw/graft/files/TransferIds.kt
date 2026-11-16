package kr.jjw.graft.files

/** Allocates transfer ids that cannot collide with a transfer already in flight. */
object TransferIds {
    fun next(active: Collection<UInt>): UInt = (active.maxOrNull() ?: 0u) + 1u
}
