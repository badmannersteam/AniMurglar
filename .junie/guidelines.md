This project is a desktop Kotlin app (based on the Jetbrains Compose), named AniMurglar.
It downloads anime raws from nyaa.si and dubs from different sites and merges them into one.

When writing code:
- don't add excessive comments, comment only difficult and not obvious parts
- don't write tests unless explicitly asked
- for checking completeness don't search for tests, running the project build should be enough
- prefer the provided IDE tools to terminal commands
- AVOID CALLING `GRADLEW` DIRECTLY IN TERMINAL, USE PROVIDED IDE TOOLS TO BUILD PROJECT!
- ask questions if something is unclear or have multiple ways to do
- Don't duplicate code – follow DRY principle.
- Use kotlin idioms and language features where possible:
  - `when` instead of `if` (except one-liners)
  - `error()`/`require()`/`check()` instead of `if(...) throw ...`
  - expression body methods instead of block body methods when possible without specifying return type
  - kotlin stdlib functions instead of java ones
  - kotlin string interpolation instead of `{}` in logging statements
- Don't overuse `ifBlank`, `trim`, `takeUnless`, `takeIf`, `isBlank`, `orEmpty`, etc and nullable fields and args - operations should fail fast if some required values are not present and be validated once on UI/Store level, not silently fallbacks to nonsense values.
- fields and args must be non-null as much as possible, except the cases when it is a really required business logic state.
