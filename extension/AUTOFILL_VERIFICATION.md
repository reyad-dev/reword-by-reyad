# Autofill and credential capture

The content script detects username-only steps, forms without a `form` element,
dynamically mounted fields, open shadow roots, and embedded frames. The popup's
one-click fill targets the top frame; embedded forms use their own field badges.
Hidden, disabled, read-only, search and OTP fields are excluded. Existing passwords
are not filled into fields marked as new/confirmation passwords.

Typing a password shows a save/update prompt after a 700 ms pause, also checked on
change, submission, Enter, and login/registration button clicks. A save requires
an explicit click. Already saved credentials suppress duplicate prompts. Locked
vaults show instructions to unlock the extension and retry Save.

Pending captures live only in trusted extension session storage, scoped to the
tab, frame and exact origin. They are never written to local storage before
consent. They expire after ten minutes and are removed on dismissal, successful
save, vault lock, or closing the tab. Same-origin full-page navigation can restore
the prompt; cross-origin redirects cannot read or confirm a previous capture.
Username-first forms retain the username for the password step on the same origin.

## Automated checks (2026-10-31)

Install `jsdom@26` in a temporary tooling directory (or locally with
`npm install --no-save jsdom@26`), then run:

```
node --test extension/tests/autofill.test.cjs
node --check extension/content.js
node --check extension/background.js
node --check extension/popup.js
```

The suite uses synthetic credentials and a mocked Chrome worker API. DOM tests
use jsdom with simulated field geometry. It does not replace a real Chrome test.

## Browser checks

Reload the unpacked extension at `chrome://extensions`, then reload test pages.
Test an email-only login step, a dynamic registration dialog, an embedded login,
a new-password/confirmation pair, username-first navigation, and submitting before
the typing debounce finishes. Confirm Save and Update, dismiss a prompt, lock and
unlock before saving, and confirm no prompt for unchanged saved credentials.
Test a site with a failed submission: the prompt offers the typed credentials;
it does not claim the website accepted the registration or password change.

Sites may hide fields in closed shadow roots or block extension injection; those
fields cannot be reached by this content script. Browser-internal pages do not
allow extension content scripts.
