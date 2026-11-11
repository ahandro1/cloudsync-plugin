// Use an integer for version numbers
version = 3

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Syncs watch history and watch-status lists between devices through a private GitHub repo, with optional AniList sync."
    authors = listOf("CloudSync")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Others")

    requiresResources = false
    language = "en"
}
