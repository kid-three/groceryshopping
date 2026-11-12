/**
 * Receipt Scanner → Google Sheet bridge.
 *
 * Bind this script to the spreadsheet (Extensions → Apps Script), set the
 * script property SECRET (Project Settings → Script properties), then
 * Deploy → New deployment → Web app, "Execute as: Me", "Who has access: Anyone".
 *
 * POST body (JSON):
 *   {
 *     "token": "<SECRET>",
 *     "receipt_id": "abc123",
 *     "rows": [ { "receipt_id": "...", "date": "2026-10-03", ... }, ... ]
 *   }
 *
 * Response: {"ok": true, "inserted": N, "duplicate": false}
 *       or  {"ok": true, "inserted": 0, "duplicate": true}
 *       or  {"ok": false, "error": "..."}
 */

var SHEET_NAME = 'Purchases';

// Must match the header row of the Purchases tab. Columns are located by
// header name, so the sheet's column order is never changed by this script.
var COLUMNS = [
  'receipt_id', 'date', 'time', 'photo_timestamp', 'store', 'item',
  'qty', 'unit_price', 'line_total', 'receipt_total', 'added_by', 'source'
];

function doPost(e) {
  try {
    var body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    var secret = PropertiesService.getScriptProperties().getProperty('SECRET');
    if (!secret || body.token !== secret) {
      return json_({ ok: false, error: 'unauthorized' });
    }
    var rows = body.rows;
    var receiptId = String(body.receipt_id || '');
    if (!receiptId || !Array.isArray(rows) || rows.length === 0) {
      return json_({ ok: false, error: 'receipt_id and non-empty rows are required' });
    }

    var lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try {
      var sheet = getSheet_();
      var lastCol = sheet.getLastColumn();
      var header = sheet.getRange(1, 1, 1, lastCol).getValues()[0].map(function (h) {
        return String(h).trim();
      });
      var colIndex = {};
      for (var i = 0; i < COLUMNS.length; i++) {
        var idx = header.indexOf(COLUMNS[i]);
        if (idx < 0) return json_({ ok: false, error: 'missing column in sheet: ' + COLUMNS[i] });
        colIndex[COLUMNS[i]] = idx;
      }

      // Dedupe on receipt_id.
      var lastRow = sheet.getLastRow();
      if (lastRow > 1) {
        var ids = sheet.getRange(2, colIndex.receipt_id + 1, lastRow - 1, 1).getValues();
        for (var r = 0; r < ids.length; r++) {
          if (String(ids[r][0]) === receiptId) {
            return json_({ ok: true, inserted: 0, duplicate: true });
          }
        }
      }

      var values = rows.map(function (row) {
        var out = new Array(lastCol);
        for (var c = 0; c < lastCol; c++) out[c] = '';
        COLUMNS.forEach(function (name) {
          var v = row[name];
          out[colIndex[name]] = (v === null || v === undefined) ? '' : v;
        });
        out[colIndex.receipt_id] = receiptId; // force consistency
        return out;
      });

      sheet.getRange(lastRow + 1, 1, values.length, lastCol).setValues(values);
      return json_({ ok: true, inserted: values.length, duplicate: false });
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

/** Health check: GET the web-app URL in a browser. Reveals nothing sensitive. */
function doGet() {
  return json_({ ok: true, service: 'receipt-scanner', sheet: SHEET_NAME });
}

function getSheet_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    var id = PropertiesService.getScriptProperties().getProperty('SHEET_ID');
    ss = SpreadsheetApp.openById(id);
  }
  var sheet = ss.getSheetByName(SHEET_NAME);
  if (!sheet) throw new Error('sheet tab not found: ' + SHEET_NAME);
  return sheet;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
