package com.revshield.spamprobe.accessibility.telephony

import android.content.Context
import android.telephony.TelephonyManager

/** Resolves the carrier on the observing device — i.e. which carrier's labelling we are reading. */
interface CarrierResolver {
    fun currentCarrier(): String?
}

/**
 * Reads the carrier from [TelephonyManager]. Uses the registered network operator name, falling
 * back to the SIM operator name. Neither requires a runtime permission.
 */
class TelephonyCarrierResolver(
    context: Context,
) : CarrierResolver {

    private val appContext = context.applicationContext

    override fun currentCarrier(): String? {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return tm.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
    }
}
