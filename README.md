# CloudSync

CloudSync is a plugin (extension) for the Android app CloudStream. It keeps your watch history and watch-status lists the same on several devices, for example a Google TV and a tablet. It stores the data in a private GitHub repository that you own. It can also sync your watch-status lists two ways with AniList.

## What is synced

| Synced | Not synced |
|---|---|
| Watch-status lists (Watching, Completed, On-Hold, Dropped, Plan to Watch) | App settings |
| Bookmark details | Logins |
| Favorites | Downloads |
| Subscriptions | Installed extensions |
| Continue-watching row | |
| Per-episode position and watched marks | |
| Selected season, episode and dub | |

## What you need

- A GitHub account.
- CloudStream installed on each device.
- Optional: an AniList account, if you want AniList sync.

There is no build step. The plugin is ready to install.

## Setup (about 5 minutes)

### Part A: Create a private repository for your data

1. Open this link: `https://github.com/new?name=cloudsync-data&visibility=private`
2. Keep the visibility set to Private.
3. Tick "Add a README file".
4. Press "Create repository".

### Part B: Install the CloudSync GitHub App on that repository

1. Open this link: `https://github.com/apps/cloudsync-data/installations/new`
2. Choose "Only select repositories".
3. Select ONLY your data repository (`cloudsync-data`).
4. Press "Install".

Selecting only this one repository is what limits the plugin's access to that repository alone.

### Part C: Sign in on each device

Do these steps on every device (for example, the TV and the tablet).

1. In CloudStream, go to Settings → Extensions → Add repository.
2. Enter this URL: `https://raw.githubusercontent.com/ahandro1/cloudsync-plugin/main/repo.json`
3. Install "CloudSync" from the list.
4. Open its settings by pressing the gear icon next to it.
5. Press "Sign in to GitHub". The plugin shows a short code.
6. On a phone or computer, open `github.com/login/device`, enter the code, and approve it. Nothing has to be typed on a TV.
7. The device's default CloudStream profile is linked automatically to a shared profile called "Default". Sync starts on its own.

Notes:

- Finish the first device's first sync before you sign in on a second device.
- If the app can reach more than one private repository, the plugin asks which one to use. A public repository is never used.

## Profiles

Each device has its own list of CloudStream profiles. A shared profile is the copy of your data in the private repository.

For each local profile, press "Change". You can then:

| Option in the "Change" dialog | What it does |
|---|---|
| A shared profile name | Links this local profile to that shared profile |
| "New shared profile…" | Creates a new shared profile and links this local profile to it. You can enter a name. |
| "Don't sync" | Stops syncing this local profile |

Notes:

- Two local profiles with different names on different devices stay in sync when they are linked to the same shared profile.
- One shared profile can be linked to one local profile per device.
- Unlinking never deletes anything on the device.

## AniList

1. Each linked profile has its own "Sign in to AniList" button. Different profiles can use different AniList accounts.
2. Sign-in uses a client built into the plugin, so there is nothing to create. This sign-in is separate from CloudStream's own AniList account.
3. You only need to sign in on ONE device per shared profile. A tablet or phone is the easiest choice.
4. The other devices get the result through device sync.
5. On a TV, the practical way is to sign in on the tablet instead.
6. To sign out of AniList for a profile, press "Sign out of AniList".

### Status mapping

| CloudSync list | AniList status |
|---|---|
| Watching | Current (Watching) |
| Completed | Completed |
| On-Hold | Paused |
| Dropped | Dropped |
| Plan to Watch | Planning |

AniList "Rewatching" counts as Watching.

### AniList rules

- Only the watch status is synced. Episode progress is not synced.
- A bookmark is matched to AniList in one of two ways:
  - By the AniList ID that the streaming extension provides, or
  - By an exact title match.
- Shows that cannot be matched with certainty are listed as unmatched and left alone. To see them, press "Show" next to the message.
- If a status changed on both sides, the more recent change wins.
- Removing a bookmark in CloudStream does not delete the AniList entry.
- Removing an AniList entry does not remove the bookmark in CloudStream.

### First AniList sync

The first AniList sync for a profile compares your CloudStream bookmarks with your AniList list:

| Situation | What happens |
|---|---|
| Bookmarked in CloudStream, not on AniList | Added to AniList with the matching status |
| On both, same status | Nothing changes |
| On both, different status, bookmark is older than the plugin install | AniList wins |
| On both, different status, bookmark changed after the plugin install | The more recent change wins |
| On AniList only | Nothing. The plugin never creates CloudStream bookmarks |
| Cannot be matched with certainty | Left alone and listed as unmatched |

Note: the same show bookmarked from two streaming extensions is treated as one AniList entry, so both bookmarks are kept on the same status.

## Controls

Open the plugin settings to find these controls.

| Control | What it does |
|---|---|
| Pause device sync on this device | This device stops sending and receiving changes. Changes made while paused are remembered. They are merged when you unpause. Other devices are not affected. |
| Sync devices now | Syncs immediately. Works even while device sync is paused. |
| Pause AniList sync (all devices) | Stops AniList sync on every device. Device sync continues. |
| Sync AniList now | Runs an AniList sync immediately. Works even while AniList sync is paused. It also retries unmatched titles. |
| Status lines | Shows the time of the last device sync, the time of the last AniList sync, and the last error (if any). |

Other buttons in the settings:

| Button | What it does |
|---|---|
| Sign in to GitHub | Starts the GitHub sign-in with a short code |
| Sign out of GitHub | Signs this device out of GitHub after you confirm |
| Check again | Looks again for the private repository after you install the GitHub App |
| Change | Chooses the shared profile for a local profile |
| Retry | Tries to load the list of shared profiles again |
| Close | Closes the settings |

## When sync runs

- When CloudStream starts.
- About 20 seconds after you change something.
- Once a minute while the app is open.

Nothing runs while CloudStream is closed.

## Privacy and trust

- Your data is stored only in your own private GitHub repository. The plugin author runs no server and receives no data.
- The GitHub sign-in can only reach the repositories you installed the app on, which is why you should select only the data repository.
- The GitHub App belongs to the plugin author. GitHub Apps let their owner create a private key that could access the repositories the app is installed on. The author states that no such key exists. If you would rather not rely on that, build your own copy with your own GitHub App (see "For developers").
- Other CloudStream extensions run in the same app and could in theory read the plugin's saved logins.
- Every sync is a commit, so the data repository's history grows slowly.

## Good to know

- The first time a device is linked to a shared profile, nothing on the device is deleted. If the same item exists on both sides, the copy already in the repository wins.
- If the same item is changed on two devices while both are offline, the later change wins. The later change is decided by the device clock.
- A synced bookmark only opens on a device that has the same streaming extension installed.
- A device that has been offline for more than 180 days can bring back items that were deleted on other devices.
- The plugin relies on CloudStream's internal data format. A future CloudStream update may need a plugin update.

## Troubleshooting

| Problem | What to check |
|---|---|
| "Repository not found, check the URL and try VPN" when adding the repository in CloudStream | The device cannot reach raw.githubusercontent.com. Check the URL. Try opening it in the device's browser. Or enable the jsDelivr / GitHub proxy option in CloudStream's settings. |
| The plugin says the app is not installed on any repository | Open `https://github.com/apps/cloudsync-data/installations/new`, install the app on your data repository, then press "Check again". |
| The plugin says the sync repository must be private | Make the data repository private, or install the app on a private repository. |
| "Still no repository found" after sign-in | Make sure the GitHub App is installed on the private repository. Then press "Check again". |
| "Your GitHub sign-in expired" | Press "Sign in to GitHub" again. Your links and data are kept. |
| Sign-in code expired | Press "Sign in to GitHub" again. |
| The status line shows an error | Read the error text. Then press "Sync devices now" or "Sync AniList now". |
| Items are not syncing | Check that device sync is not paused on this device. Check that the local profile is linked (not "Not synced"). |

## For developers and building your own copy

- The pure logic (merge rules, AniList status merge, title matching) is in `CloudSync/src/main/kotlin/com/cloudsync/core/`.
- Its unit tests are in `core-test/`.
- To run the tests, use `./gradlew -p core-test test`. This needs only JDK 17.
- The plugin itself is built by GitHub Actions. Building it on your own computer needs the Android SDK.
- The data in the private repository has this layout:
  - `index.json`: the list of shared profiles and the AniList pause switch.
  - `profiles/<id>.json`: one file for each shared profile.

### Build your own copy

1. Fork the repository `ahandro1/cloudsync-plugin` on GitHub.
2. Create your own GitHub App with these settings:
   - Device Flow: on.
   - "Expire user authorization tokens": off.
   - Webhook: off.
   - Repository permissions, Contents: "Read and write".
   Install it on your data repository.
3. Optional: create your own AniList API client. Set the redirect URL to exactly `https://anilist.co/api/v2/oauth/pin`.
4. In `CloudSync/src/main/kotlin/com/cloudsync/Config.kt`, put in your client IDs. Put your GitHub App's URL slug in `GITHUB_APP_SLUG`.
5. Change the two URLs in `repo.json` to your fork. Change the default repository name in the root `build.gradle.kts` to your fork as well.
6. Push to `main`. GitHub Actions publishes the plugin to a branch called `builds`.
7. If the build fails, the log is published to `build-logs/build.log`. Open that file to see the error.

Client IDs are not secrets. They can be stored in the public repository.

## License

GPL-3.0. See the `LICENSE` file.
