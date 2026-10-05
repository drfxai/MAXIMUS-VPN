package com.example.vpn.engine.registry

/**
 * How far the app has come with a protocol or feature. Only [VERIFIED_WORKING] may be presented to
 * users as working.
 */
enum class CapabilityState {
    /** Not imported, not run. */
    NONE,
    /** Links or configs are read and saved. */
    PARSE_SUPPORTED,
    /** A bundled engine runs it. */
    ENGINE_SUPPORTED,
    /** Real traffic went through it in an automated test against a real server. */
    VERIFIED_WORKING
}
