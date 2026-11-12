# Receipt Scanner

Camera → on-device OCR (ML Kit) → Claude (text only) or offline regex parser → review → append rows to the `Purchases` tab via an Apps Script web app. See `CLAUDE.md` for goals.

## 1. Apps Script (once)

1. Open the sheet → **Extensions → Apps Script**. Replace `Code.gs` with [apps-script/Code.gs](apps-script/Code.gs).
2. **Project Settings → Script properties** → add `SECRET` = a long random string (`openssl rand -hex 24`).
3. **Deploy → New deployment → Web app**: *Execute as: Me*, *Who has access: Anyone*. Authorize, copy the `/exec` URL.
4. Test from the Mac (adds one row with `source=test`; delete it after):
   ```sh
   SCRIPT_URL='https://script.google.com/macros/s/…/exec' SECRET='…' ./apps-script/test.sh
   ```
   Expect: inserted 1 → duplicate true → unauthorized.

After editing `Code.gs` later: **Deploy → Manage deployments → edit → New version** (same URL).

## 2. local.properties (git-ignored)

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
anthropic.apiKey=sk-ant-...        # empty = offline parser only
# anthropic.model=claude-haiku-5-5
sheet.scriptUrl=https://script.google.com/macros/s/…/exec   # optional: pre-fills Settings
sheet.secret=…                                                # optional: pre-fills Settings
```

The key is baked into the APK. Anyone who has the APK could extract it, so set a monthly spend limit in the Anthropic Console.

## 3. Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest      # parser tests against testdata/
./gradlew :app:assembleRelease        # → app/build/outputs/apk/release/app-release.apk (~32 MB, arm64)
```

Optional live check of the Claude parser (a fraction of a cent):
`ANTHROPIC_API_KEY=sk-ant-... ./gradlew :app:testDebugUnitTest --tests '*ClaudeParserLiveTest*'`

## 4. Install on the phones

**adb (USB):** on the phone, Settings → About phone → tap *Build number* 7× → Developer options → enable *USB debugging*. Plug in, accept the prompt, then:
```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Sharing the file:** send `app-release.apk` (Drive, Telegram, cable). On the phone open it, allow *Install unknown apps* for that app when asked, and install. Updates install over the old version as long as they're built on the same Mac (same debug signing key).

First launch: allow the camera, tap ⚙ → pick *Kinich* or *Alba* → Save.

## Notes

- Photos stay in the app's private storage; only OCR text is sent to Claude.
- A saved receipt is written to a local queue before uploading and removed only after the script confirms. Offline saves retry automatically when the network returns (or ⚙ → *Retry uploads now*).
- `receipt_id` is a hash of store + date + time + total, so re-scanning a receipt is rejected as a duplicate by the script.
- If the printed date can't be read, `date` falls back to the photo's date; `photo_timestamp` is always the capture time.
- Review screen → *Show OCR text* → *Copy* is the easy way to collect new `testdata/*.ocr.txt` cases.
