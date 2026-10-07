# Rotating exposed tokens

Two credentials were exposed in this working tree and have been removed from
disk. **Both should still be considered compromised and rotated**, because
exposure included the conversation history of an LLM session, which may be
logged or transmitted somewhere outside this machine.

## GitHub (`ghp_...`)

The token was embedded in the git remote URL, i.e. plaintext in
`.git/config`, for the whole history of this project.

Already done:

- `git remote set-url origin https://github.com/HeshamAbuShaban/iqra.git`

  The remote is now credential-free. `git ls-remote` and `git push` continue to
  work through the `gh` credential helper, so **no token is stored in this
  repository or in `~/.git-credentials`**.

Still required, and only the account owner can do it:

1. <https://github.com/settings/tokens> — revoke the exposed token.
2. Create a replacement. A fine-grained token scoped to this one repository is
   enough; it needs `contents: read and write` and nothing else.
3. Store it without writing it into a URL:

   ```
   gh auth login
   # or
   git config --global credential.helper '!gh auth git-credential'
   ```

**Do not** put the replacement back into the remote URL. That is what caused
this.

## Hugging Face (`hf_...`)

The token was pasted into an LLM conversation while fetching the
`Quran-Lab/quran-tajweed-phonetics` dataset.

Already done: no token was ever written to a tracked file, a dotfile, or the
git history. `engine/replay/fetch_word_table_inputs.sh` fetches that dataset
unauthenticated, because it is ungated — so **the app needs no HF token at all**.

Still required:

1. <https://huggingface.co/settings/tokens> — revoke the exposed token.
2. Do not create a replacement unless something else needs one.

The gated model weights (`engine/shootout/weights/zipformer/`) are staged on the
phone at `/sdcard/Iqra` and are **never committed**. If they need re-fetching,
that is a deliberate manual step, not something to wire into a script.

## Verifying afterwards

```
git config --get remote.origin.url          # must contain no token
git log --all -p -S"ghp_" --oneline         # must be empty: no token in history
```

For a whole-tree scan, note that the pattern itself matches this file, because it
quotes the prefixes. Exclude the docs that are *about* the exposure:

```
git ls-files \
  ':!scripts/rotate-exposed-tokens.md' \
  | xargs grep -lE 'ghp_[A-Za-z0-9]{20,}|hf_[A-Za-z0-9]{20,}'   # must be empty
```

A real GitHub token is 40 characters after the `ghp_` prefix and a real Hugging
Face token is 34 or more after `hf_`; requiring that length is what separates a
live credential from the placeholder text above.
