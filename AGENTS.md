# YTM Head Unit workflow

- For every project change, start with [upstream ytm-rs](https://github.com/grapedjuice/ytm-rs). Use both REA and Craft Anything to inspect the relevant source or release artifacts and compare the Android result with upstream. Record what was checked and any remaining differences. If a tool is unavailable, report the blocker and continue the checks that are possible.
- Create a separate Git branch for each experiment, using a descriptive name such as `experiment/compact-player`. Push experimental builds to their own branch. Merge into `main` only when the experiment is accepted.
- Commit the relevant changes and push after each successful build. Push to the corresponding branch and verify the remote ref. Report any failed push and its cause.
- Treat `iDash3`, `iDASH-RELEASED`, and `idash3-google-` as read-only references. Put implementation changes in this repository.

## When the user says `scan kebocoran`

- Run [Gitleaks](https://github.com/gitleaks/gitleaks) against local Git history and workspace files, including ignored files where relevant. Always redact findings with `--redact=100`. Check the delivered APK for credentials, private keys, and unintended packaged files.
- Run [Semgrep](https://github.com/semgrep/semgrep) locally against first-party source using Java, Android, and secrets rules. Turn off metrics. Review findings and report affected paths and line numbers, never secret values.
- Use [ZAP](https://github.com/zaproxy/zaproxy) for passive analysis when an authorized local HTTP target or a sanitized HTTP capture is available. Keep active scan and spidering disabled.
- Use [Strix](https://github.com/usestrix/strix) as a reference for defensive review coverage. Keep autonomous attack agents and exploit validation outside this workflow.
- Report each tool's result, including skips and their reasons. Keep scanner reports out of commits and preserve the read-only launcher checkouts.

## Permanent Google authentication lock

Google authentication is frozen across all future updates, dependency changes, refactors and iDash integration. Explicit user approval is required for any exception. The lock covers GoogleSignIn, WebSession, account and verification logic in MusicApi and MainActivity, WebView flow and configuration, cookies, redirects, session storage and persistence, account detection, account-menu classification, tokens, ACCOUNT_MENU_SIGNED_OUT fixes, authentication diagnostics, dependencies, manifest entries and regression tests. Preserve the application ID, signing configuration and existing sessions. Never clear or reset accounts, force sign-in again, or alter authentication indirectly through a shared dependency.

Before changing the project, record the commit and working tree, identify the protected code and configuration, and establish a baseline. Preserve existing user changes. Before delivery, compare the protected code and configuration with that baseline, run the unmodified authentication regression tests, verify the application ID and certificate, and report any limits on live verification. Report separate authentication issues without fixing them automatically. If another feature conflicts with this lock, isolate or defer that feature.

## Full native Android port

Use the tracked parity matrix and implementation status in docs/ as the handoff between versioned checkpoints. Start from upstream evidence and keep each compatible feature accounted for. Keep startup and core features automatic, preserve saved overrides, and require explicit consent for billable services. Prefer verified compatible providers and report real provider, emulator and hardware limits. Never invent timing, translations, video availability or measurements.

Target Android API 27+, ARM32/ARM64, GLES 2.0 and the reference 1024 × 600 head unit. Auto performance is the default, with Ultra Lite and Lite fallbacks, Balanced for 512 MB–2 GB and High Quality for capable 2–4 GB and larger devices. Prioritize uninterrupted audio and bounded background work. Prefer a 10–20 MB APK and do not exceed 30 MB without explicit approval. Song is the startup mode. Video requires reliable permitted parked-use state and must obey OEM restrictions. Preserve the authentication lock above across all port phases.

Develop and exercise independent video components with authorized media in an emulator or permitted non-driver-display environment. Driver-display video requires an OEM-supported, reliable parked signal. An unknown state keeps driver-display playback unavailable.

## Rule 12 — Absolute Lyrics Engine Lock

State: **PENDING FINAL VERIFICATION**. Preserve the verified lyrics behavior during unrelated work. Finish targeted lyrics work in a dedicated checkpoint. Save the exact commit and APK version, protected file and method inventory, resources, dependencies, preference defaults and regression results. Mark the state **ACTIVE PERMANENT LOCK** only after verified runtime behavior and explicit user acceptance of that checkpoint.

After activation, changes to protected lyrics code and behavior require explicit user approval. Protection covers provider order, discovery and matching, caching, LRC/TTML parsing, timestamp validation, timing and scroll, original/alternate rendering, theme lyric parameters, karaoke, all motion and fades, romanization and the bundled Japanese dictionary, translation and English defaults, lyrics preferences, storage, assets, dependencies and regression tests. In shared files protect lyrics-related methods, call sites and behavior. Keep video, theme, playback, refactoring and performance changes isolated from the lyrics path. Compare the protected scope against the accepted baseline and rerun unchanged lyrics regressions for every meaningful checkpoint. Record new lyrics issues and request explicit approval before changing protected behavior.

## Continuous checkpoint development

Continue compatible parity work independently through meaningful, verified checkpoints. Preserve existing work, record the exact next unfinished task, and keep working on independent features when another feature requires unavailable hardware, authorization, or provider access. Revisit a known blocker when there is new evidence. Report each verification limit and obtain the required authorization for irreversible actions.
