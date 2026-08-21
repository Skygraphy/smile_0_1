// The anon/public key is designed to be embedded in client apps -- it is
// not a secret (Row Level Security is what actually protects data, not this
// key). Safe to commit.
class SupabaseConfig {
  static const url = 'https://slvxboptfoyrynuvmmtz.supabase.co';
  static const anonKey =
      'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InNsdnhib3B0Zm95cnludXZtbXR6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODcyOTgwODksImV4cCI6MjEwMjg3NDA4OX0.EJkZnsvFBb1lFdSPndIRSE9cmu2fE7nA7dZW5pkWLqI';
}
