package android.util
object Base64 { const val DEFAULT = 0; const val NO_WRAP = 2; const val URL_SAFE = 8; const val NO_PADDING = 1
  fun decode(s: String, flags: Int): ByteArray = java.util.Base64.getMimeDecoder().decode(s.replace('-', '+').replace('_', '/'))
  fun decode(b: ByteArray, flags: Int): ByteArray = decode(String(b), flags)
  fun encodeToString(b: ByteArray, flags: Int): String = java.util.Base64.getEncoder().encodeToString(b) }
