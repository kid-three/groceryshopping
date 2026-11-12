# Receipt Scanner (Android, private use)

Small Android app for two users (me and my wife). Open the app → camera opens → take a photo of a shopping receipt → the app reads it with OCR, extracts the purchase data and appends it to a shared Google Sheet that feeds our grocery dashboard.

Never published to the Play Store. Installed by sideloading (debug or release APK via adb or a shared file). Keep everything as small and simple as possible.

## Goals

- App launches straight into the camera, with no home screen, login wall or onboarding.
- One tap to capture, then a quick review/edit screen, then one tap to save.
- Extract from each receipt: store name, receipt date and time, line items (name, quantity, unit price, line total), receipt total, and payment total if present.
- Also store the photo's capture timestamp as a fallback when the printed date can't be read.
- Append one row per line item to the shared Google Sheet.
- Works for receipts from any store (not just Carrefour), mostly Spanish receipts in euros.

## Non-goals

- No Play Store release, accounts, backend of our own, analytics, ads or multi-tenant features.
- No in-app dashboard. The Google Sheet is the dashboard.
- No iOS.

## Suggested stack (confirm or adjust before building)

- Kotlin, Jetpack Compose, single Activity, minSdk 26.
- CameraX for capture (ImageCapture use case).
- ML Kit Text Recognition (on-device, Latin script) for OCR. It is free and works offline.
- Structuring the OCR text into items: send the OCR text (optionally the image) to the Claude API and ask for strict JSON. Receipt layouts vary too much for regexes alone. Use a cheap, fast model. Keep the API key in `local.properties` exposed via BuildConfig. It is never committed, and since the app is private and sideloaded that is acceptable. Offer a regex-only fallback parser for when there is no network.
- Writing to Google Sheets: prefer a **Google Apps Script web app** bound to the sheet that exposes a `doPost` endpoint and appends rows. The app just POSTs JSON with a shared secret token. This avoids OAuth, Google Cloud setup and SHA-1 fingerprints. Alternative if wanted: Sheets API v4 with Google Sign-In.
- Offline behaviour: if the POST fails, keep the parsed receipt in a small local queue (Room or a JSON file) and retry later. Never lose a photo or a parsed receipt.

## Data model

One row per line item, appended to a `Purchases` tab:

| column          | notes                                                                      |
| --------------- | -------------------------------------------------------------------------- |
| receipt_id      | unique per receipt (uuid or hash of store+datetime+total), used for dedupe |
| date            | receipt date from the printed receipt, ISO `yyyy-mm-dd`                    |
| time            | receipt time `HH:mm` if printed                                            |
| photo_timestamp | when the picture was taken, ISO with timezone                              |
| store           | store name and branch if printed                                           |
| item            | product name as printed, plus a normalized name if easy                    |
| qty             | units, or weight for items sold by kg                                      |
| unit_price      | price per unit or per kg                                                   |
| line_total      | price for that line after line discounts                                   |
| receipt_total   | total of the whole receipt, repeated on each row                           |
| added_by        | which of us (a simple setting)                                             |
| source          | `app-ocr` (the Carrefour history imported separately uses `carrefour-web`) |

Keep the column order stable. The existing dashboard and analysis tabs depend on it. Ask me for the sheet ID and the exact current header row before writing any code that appends to it.

## Receipt parsing notes (Spanish tickets)

- Amounts use a comma as the decimal separator (`1,35`), sometimes with `€` or `EUR`.
- Abbreviated, truncated product names are normal (`Queso feta crf 200g`).
- Weighed items usually print as `0,532 kg x 4,99 €/kg` on a second line under the product.
- Multi-quantity lines can be `2 x 1,89` or a separate quantity column.
- Discounts and promotions can appear as separate negative lines (`DTO`, `Promo`, `-0,50`). Attach them to the preceding item or keep them as their own line with a negative total.
- Ignore VAT/IVA breakdown tables, loyalty-card points, card terminal text and the footer. Use the `TOTAL` line to validate: the sum of line totals should match the receipt total. If it doesn't, flag the receipt on the review screen instead of saving silently.
- Dates are `dd/mm/yyyy` or `dd-mm-yy`. Locale is es-ES, timezone Europe/Madrid.

## Screens

1. **Camera** (launch screen): full-screen preview, capture button, gallery-pick as a secondary option.
2. **Review**: shows the parsed store, date and a list of items with quantity and price, all editable. Highlights a mismatch between the sum of items and the total. Buttons: Save, Retake.
3. **Settings** (tiny): Apps Script URL, shared secret, "who am I" name. Stored in DataStore.

## Quality bar

- Test the parser against real receipts. Keep a `testdata/` folder with a handful of anonymized receipt photos and the expected JSON, and unit-test the parsing and JSON-validation code against them.
- Photos never leave the phone unless I choose the image-to-Claude option. By default only OCR text is sent.
- Don't commit API keys, the Apps Script URL or the shared secret.

## First steps for Claude Code

1. Ask me for: the Google Sheet ID, the current header row, and 3 to 5 sample receipt photos or their OCR text.
2. Propose the architecture above in a short plan, flagging anything you would change, and wait for my OK.
3. Create the Apps Script (`Code.gs`) first and test it with curl, then the Android project.
4. Build the camera → OCR → review → save flow end to end with the smallest possible UI before polishing anything.
5. Explain how to build the APK and install it on my phone and my wife's phone (developer options + adb, or sharing the APK file).
