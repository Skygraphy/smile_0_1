// The anon/public key is designed to be embedded in client apps -- it is
// not a secret (Row Level Security is what actually protects data, not this
// key). Safe to commit.
class SupabaseConfig {
  static const url = 'https://slvxboptfoyrynuvmmtz.supabase.co';
  static const anonKey = 'sb_publishable_n1TNMUrrnYVZyQ6v9Ek_Rg_NIzZnVK-';
}
