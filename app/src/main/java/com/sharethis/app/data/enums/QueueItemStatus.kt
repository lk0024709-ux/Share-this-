package com.sharethis.app.data.enums

/** Status of one file inside a transfer queue. */
enum class QueueItemStatus {
    WAITING,
    ACTIVE,
    COMPLETED,
    VERIFIED,
    FAILED,
    SKIPPED,
    CANCELLED
}
