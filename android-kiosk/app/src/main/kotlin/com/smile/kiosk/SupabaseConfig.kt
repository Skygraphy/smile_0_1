package com.smile.kiosk

// The publishable/anon key is designed to be embedded in client apps -- not
// a secret (Row Level Security / the device-JWT scheme is what actually
// protects data). Safe to commit. Same project as the sender app.
object SupabaseConfig {
    const val URL = "https://slvxboptfoyrynuvmmtz.supabase.co"

    // The project migrated to the new publishable/secret API key system.
    // The legacy anon JWT still works as the `apikey` header for Edge
    // Function calls (routed through Kong), but direct PostgREST access
    // (/rest/v1/..., used by the compliance worker) rejects it with a 401
    // "Invalid API key" -- only this new-format key works there. Use it
    // everywhere for consistency.
    const val ANON_KEY = "sb_publishable_n1TNMUrrnYVZyQ6v9Ek_Rg_NIzZnVK-"
}
