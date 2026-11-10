# CloudSync

CloudSync is a plugin (extension) for the Android app CloudStream. It keeps your watch history and watch-status lists the same on several devices, for example a Google TV and a tablet. It stores the data in a private GitHub repository. It can also sync your watch-status lists two ways with AniList.

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

## Before you start

You need:

- A GitHub account.
- CloudStream installed on each device.
- Access to a computer for the one-time setup. The setup uses a web browser and a text editor.
- Optional: an AniList account, if you want AniList sync.

## Part 1: One-time setup on a computer

Follow these sections in order.

### 1. Create two GitHub repositories

1. Create a PUBLIC repository for the plugin code. For example, `cloudsync-plugin`.
2. Create a PRIVATE repository for your data. For example, `cloudsync-data`.
3. The private repository can stay completely empty. The plugin creates the files it needs.

### 2. Create a GitHub App

1. On GitHub, go to Settings, then Developer settings, then GitHub Apps, then New GitHub App.
2. Enter any unique name.
3. Set Homepage URL to the URL of the public repository. Any valid URL works.
4. Under Webhook, untick "Active".
5. Tick "Enable Device Flow".
6. Untick "Expire user authorization tokens". Look under the optional features after creation, or in the form if it is shown there.
7. Under Repository permissions, find Contents and set it to "Read and write".
8. Under "Where can this GitHub App be installed", choose "Only on this account".
9. Create the app.
10. Copy the Client ID. It starts with `Iv`.
11. Choose "Install App", then install it on your account.
12. Choose "Only select repositories". Select ONLY the private data repository.

### 3. Create an AniList API client (only if you want AniList sync)

1. Go to anilist.co. Open Settings, then Developer, then Create New Client.
2. Enter any name.
3. Set the Redirect URL to exactly `https://anilist.co/api/v2/oauth/pin`.
4. Copy the client ID. It is a number.

### 4. Fill in the placeholders

Open these two files in a text editor.

**File 1:** `CloudSync/src/main/kotlin/com/cloudsync/Config.kt`

| Replace this text | With this value |
|---|---|
| `REPLACE_WITH_GITHUB_APP_CLIENT_ID` | The GitHub App Client ID from step 2 |
| `REPLACE_WITH_ANILIST_CLIENT_ID` | The AniList client ID from step 3 |

**File 2:** `repo.json` (in the project root)

| Replace this text | With this value |
|---|---|
| `OWNER/REPO` | Your public repository, for example `yourname/cloudsync-plugin` |

These IDs are not secrets. They can be stored in the public repository.

### 5. Push the code to GitHub

1. Push this folder to the `main` branch of the public repository.
2. GitHub Actions runs the "Build" workflow. It compiles the plugin and publishes it to a branch called `builds`.
3. If the build fails, the log is published to a branch called `build-logs`, in a file called `build.log`. Open that file to see the error.

## Part 2: Install on each device

Do these steps on every device (for example, the TV and the tablet).

1. In CloudStream, go to Settings, then Extensions, then Add repository.
2. Enter this URL, with your public repository filled in:
   `https://raw.githubusercontent.com/ahandro1/cloudsync-plugin/main/repo.json`
3. Open that repository in the Extensions list. Install "CloudSync".
4. Open the plugin settings. Press the gear icon next to the plugin in the extensions list.
5. Press "Sign in to GitHub". The plugin shows a short code.
6. On a phone or computer, open `github.com/login/device`. Enter the code and approve it. Nothing has to be typed on the TV.
7. The device's default CloudStream profile is linked automatically to a shared profile called "Default". Sync starts on its own.

## Part 3: Profiles

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

## Part 4: AniList

1. Each linked profile has its own "Sign in to AniList" button. Different profiles can use different AniList accounts.
2. This sign-in belongs to the plugin. It is separate from CloudStream's own AniList account.
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

## Part 5: Controls

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

## Good to know

- The first time a device is linked to a shared profile, nothing on the device is deleted. If the same item exists on both sides, the copy already in the repository wins.
- If the same item is changed on two devices while both are offline, the later change wins. The later change is decided by the device clock.
- A synced bookmark only opens on a device that has the same streaming extension installed.
- A device that has been offline for more than 180 days can bring back items that were deleted on other devices.
- Other CloudStream extensions run in the same app. In theory, they could read the plugin's saved logins. The GitHub login can only reach the one private data repository.
- The plugin relies on CloudStream's internal data format. A future CloudStream update may need a plugin update.

## Troubleshooting

| Problem | What to check |
|---|---|
| The build failed | Open the `build.log` file on the `build-logs` branch. |
| "Still no repository found" after sign-in | Make sure the GitHub App is installed on the private repository. Then press "Check again". |
| Sign-in code expired | Press "Sign in to GitHub" again. |
| The status line shows an error | Read the error text. Then press "Sync devices now" or "Sync AniList now". |
| Items are not syncing | Check that device sync is not paused on this device. Check that the local profile is linked (not "Not synced"). |

## For developers

- The pure logic (merge rules, AniList status merge, title matching) is in `CloudSync/src/main/kotlin/com/cloudsync/core/`.
- Its unit tests are in `core-test/`.
- To run the tests, use `./gradlew -p core-test test`. This needs only JDK 17.
- The plugin itself is built by GitHub Actions. Building it on your own computer needs the Android SDK.
- The data in the private repository has this layout:
  - `index.json`: the list of shared profiles and the AniList pause switch.
  - `profiles/<id>.json`: one file for each shared profile.
