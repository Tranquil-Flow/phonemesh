package org.phonemesh.core

/** What the user sees for one sent message (always a shape plus a colour in the UI). */
enum class DeliveryStatus {
    /** 🕓 grey clock. */
    WAITING,
    /** 📡 "parti": a group message left the user's radio; final for groups. */
    SENT,
    /** ✓ green tick: the destination radio confirmed (private only). */
    DELIVERED,
    /** ⚠ "Pas confirmé" + Renvoyer. Never called a failure. */
    UNCONFIRMED,
    /** ⚠ "Échec" + Renvoyer. */
    FAILED,
    /** 🕓 "En attente d'une radio": no radio could take it yet; it goes by itself once one is ready, even after a restart. */
    QUEUED;

    val canResend: Boolean get() = this == UNCONFIRMED || this == FAILED
}

/** The radio's per-packet reports; the same names as org.phonemesh.ble.DeliveryState (core has no Android dependency). */
enum class RadioReport { SUBMITTING, QUEUED, ACKNOWLEDGED, FAILED, UNCONFIRMED }

/** The spec's delivery table. Final states change only when the user resends. */
object DeliveryRules {
    fun next(current: DeliveryStatus, report: RadioReport, group: Boolean): DeliveryStatus = when {
        current == DeliveryStatus.DELIVERED || current == DeliveryStatus.FAILED || current == DeliveryStatus.UNCONFIRMED -> current
        report == RadioReport.SUBMITTING -> current
        report == RadioReport.QUEUED -> if (group) DeliveryStatus.SENT else DeliveryStatus.WAITING
        report == RadioReport.ACKNOWLEDGED -> if (group) current else DeliveryStatus.DELIVERED
        report == RadioReport.UNCONFIRMED -> if (group && current == DeliveryStatus.SENT) DeliveryStatus.SENT else DeliveryStatus.UNCONFIRMED
        else -> DeliveryStatus.FAILED
    }

    /** At app start: a clock left from before can no longer finish; a group message at 📡 stays. */
    fun onReopen(current: DeliveryStatus): DeliveryStatus =
        if (current == DeliveryStatus.WAITING) DeliveryStatus.UNCONFIRMED else current
}
