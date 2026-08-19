package com.revshield.spamprobe.accessibility.config

/**
 * Packages whose call screens the probe may inspect.
 *
 * A key distinction for RevShield: a *carrier* / built-in caller-ID spam label appears in the
 * NATIVE OS dialer/in-call UI, whereas third-party apps (Truecaller, Hiya) render their OWN
 * crowdsourced verdicts — which are not the carrier's. By default the probe reads only [NATIVE]
 * packages, so what it detects is attributable to the carrier / built-in caller-ID. Third-party
 * apps are opt-in (see AppContainer.monitorThirdPartyDialers).
 *
 * These are configuration: add the package observed on a target device to extend coverage.
 * Unrecognised packages during a call are logged so new ones are easy to discover on real hardware.
 */
object DialerPackages {

    /** Native OS dialer / in-call UIs, where carrier and built-in caller-ID labels appear. */
    val NATIVE: Set<String> = setOf(
        // AOSP / Google
        "com.android.dialer",
        "com.android.incallui",
        "com.google.android.dialer",
        // Samsung
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        // Xiaomi / MIUI
        "com.miui.dialer",
        "com.android.incallui.xiaomi",
        // OnePlus / Oppo / Realme (ColorOS)
        "com.oneplus.dialer",
        "com.coloros.phone",
        "com.oplus.dialer",
        // Vivo
        "com.vivo.dialer",
    )

    /** Third-party caller-ID apps with their own spam databases (NOT the carrier). Opt-in only. */
    val THIRD_PARTY: Set<String> = setOf(
        "com.truecaller",
        "com.hiya.star",
    )

    /**
     * True if [packageName] is a call screen the probe should inspect. Only native packages count
     * unless [includeThirdParty] is set.
     */
    fun isMonitored(packageName: CharSequence?, includeThirdParty: Boolean = false): Boolean {
        if (packageName == null) return false
        val pkg = packageName.toString()
        return NATIVE.contains(pkg) || (includeThirdParty && THIRD_PARTY.contains(pkg))
    }

    /**
     * Surfaces that render an incoming call as a heads-up BANNER instead of a full screen (when the
     * phone is unlocked and in use). The banner is drawn by SystemUI, not the dialer, so without
     * these a call answered-from-banner is never captured at all. Only consulted while a call is
     * actually ringing, so ordinary SystemUI chrome cannot leak in.
     */
    val CALL_BANNER: Set<String> = setOf(
        "com.android.systemui",
        "com.miui.systemui.plugin",
    )

    fun isCallBanner(packageName: CharSequence?): Boolean =
        packageName != null && CALL_BANNER.contains(packageName.toString())

    /** True if [packageName] is the NATIVE OS dialer — i.e. a carrier / built-in caller-ID verdict. */
    fun isNative(packageName: CharSequence?): Boolean =
        packageName != null && NATIVE.contains(packageName.toString())

    /** True if [packageName] is a third-party caller-ID app (Truecaller/Hiya) — its OWN verdict. */
    fun isThirdParty(packageName: CharSequence?): Boolean =
        packageName != null && THIRD_PARTY.contains(packageName.toString())
}
