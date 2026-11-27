# YTM Head Unit workflow

- For every project change, start with [upstream ytm-rs](https://github.com/grapedjuice/ytm-rs). Use both REA and Craft Anything to inspect the relevant source or release artifacts and compare the Android result with upstream. Record what was checked and any remaining differences. If a tool is unavailable, report the blocker and continue the checks that are possible.
- Create a separate Git branch for each experiment, using a descriptive name such as `experiment/compact-player`. Push experimental builds to their own branch. Merge into `main` only when the experiment is accepted.
- Commit the relevant changes and push after each successful build. Push to the corresponding branch and verify the remote ref. Report any failed push and its cause.
- Treat `iDash3`, `iDASH-RELEASED`, and `idash3-google-` as read-only references. Put implementation changes in this repository.
