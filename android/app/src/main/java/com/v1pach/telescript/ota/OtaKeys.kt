package com.v1pach.telescript.ota

/** Public half of the release signing key (the private half lives only on the maintainer's machine). */
object OtaKeys {
    const val PUBLIC_KEY_HEX = "5597bc6a40e0c335f33d8cae795272a9dd053bf40a7fe2e6af2c48a2fa89f63b"
    val publicKey: ByteArray get() = OtaCore.hex(PUBLIC_KEY_HEX)
}
