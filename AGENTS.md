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
