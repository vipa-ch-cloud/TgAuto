package com.v1pach.telescript.ota

/**
 * Public halves of the release signing keys (the private halves live only on the maintainer's machine).
 * Two keys are trusted as official: the original one and the one introduced in 1.9.6. A package signed by
 * either is "official" (may auto-apply); [publicKey] is the primary, [alsoOfficial] the rest.
 */
object OtaKeys {
    const val PUBLIC_KEY_HEX = "5597bc6a40e0c335f33d8cae795272a9dd053bf40a7fe2e6af2c48a2fa89f63b"
    const val PUBLIC_KEY_2_HEX = "611efe75540e246b15ebc095534e1bf44474d065e9b931b9ee6aa653c22f6015"
    val ALL_HEX = listOf(PUBLIC_KEY_HEX, PUBLIC_KEY_2_HEX)
    val publicKey: ByteArray get() = OtaCore.hex(PUBLIC_KEY_HEX)
    val alsoOfficial: List<ByteArray> get() = listOf(OtaCore.hex(PUBLIC_KEY_2_HEX))
    val all: List<ByteArray> get() = listOf(publicKey) + alsoOfficial
    fun isOfficial(key: ByteArray) = all.any { it.contentEquals(key) }
}
