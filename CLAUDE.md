# OpenTune

## Commits

Every commit is authored and committed by Shivam
(`Shivam <78946189+justshivv@users.noreply.github.com>`). Before committing,
check `git config user.name` and `git config user.email` and set them if they
say anything else. Don't add `Co-Authored-By`, `Claude-Session` or any other
attribution lines to commit messages or pull requests.

## Checks

CI runs `./gradlew assembleDebug testDebugUnitTest lintDebug`, then
`./gradlew assembleRelease`. Run the same before pushing.
