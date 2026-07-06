// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import anki.decks.Deck
import anki.decks.DeckKt.FilteredKt.searchTerm
import anki.decks.DeckKt.filtered
import anki.decks.filteredDeckForUpdate
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.libanki.Collection
import com.ichi2.utils.ViewGroupUtils.setRenderWorkaround
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Vantage readiness dashboard: shows the three scores with their ranges, exam
 * coverage, and the best next topic in a WebView, matching the desktop UI.
 *
 * The page is a self-contained asset (assets/vantage/index.html) rendered from
 * the same web files as the desktop add-on, so the phone and desktop look alike.
 */
class VantageDashboardActivity : AnkiActivity() {
    private lateinit var webView: WebView

    // True once the dashboard asset has finished loading, so a recap injected from
    // the reviewer-return callback (below) waits for window.vreview to exist.
    private var pageReady = false

    // Feature: flashcard-session recap. Epoch-ms marker for the start of a native
    // reviewer session (0 = none pending). On return we count exactly the cards
    // graded at/after this time from the revlog -- a real number, never an estimate.
    // Saved across recreation so a session that outlives this activity still recaps.
    private var flashcardSessionStartMs = 0L

    // Real count of cards graded in the just-finished flashcard session, awaiting the
    // recap once the page is ready (-1 = nothing pending). Not persisted: it is
    // recomputed from flashcardSessionStartMs on the reviewer-return callback.
    private var pendingRecapReviewed = -1

    // Launch AnkiDroid's own reviewer for a result (instead of the old fire-and-
    // finish()), so the Vantage activity survives the flashcard session and can show
    // a recap when the reviewer returns -- parity with the desktop reviewer's done
    // screen. The result code is irrelevant; the callback firing means "session over".
    private val reviewerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onFlashcardSessionEnded()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        // Restore a session marker that outlived a recreation (e.g. the reviewer was
        // foreground when this activity was reclaimed), so its recap still shows.
        flashcardSessionStartMs = savedInstanceState?.getLong(KEY_FLASHCARD_SESSION_START, 0L) ?: 0L
        webView = WebView(this)
        webView.setBackgroundColor(Color.WHITE)
        setContentView(webView)
        // Feature 2: ensure the daily Vantage due-cards reminder alarm is scheduled
        // (idempotent). Reuses AlarmManager + the REVIEW_REMINDERS channel.
        VantageDueReminderReceiver.schedule(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = true
        setRenderWorkaround(this)
        webView.addJavascriptInterface(Bridge(webView), "pycmdBridge")
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    // Wire the in-page Back/Refresh/Study buttons (they call pycmd) to Android.
                    view.evaluateJavascript(
                        "window.pycmd = function(c){ pycmdBridge.invoke(c); };",
                        null,
                    )
                    injectLiveScores(view)
                    // The page is ready: a recap queued by the reviewer-return callback
                    // (which can fire before load finishes after a recreation) shows now.
                    pageReady = true
                    maybeShowRecap()
                }
            }
        webView.loadUrl("file:///android_asset/vantage/index.html")
        // Feature 3: if a prior Vantage-capped session left a timebox override, put the
        // student's own timeLim back now that we are back on the dashboard.
        restoreVantageTimebox()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Keep the flashcard-session marker if this activity is reclaimed while the
        // native reviewer is foreground, so the recap still shows on recreation.
        outState.putLong(KEY_FLASHCARD_SESSION_START, flashcardSessionStartMs)
        super.onSaveInstanceState(outState)
    }

    /**
     * Compute the three scores from THIS phone's own collection (real FSRS
     * retrievability, coverage from tags, graded-review count) and render them
     * live, replacing the baked snapshot in the asset.
     */
    private fun injectLiveScores(view: WebView) {
        lifecycleScope.launch {
            val raw =
                withCol {
                    val now = TimeManager.time.intTime()
                    // Only compute retrievability for cards that actually have an
                    // FSRS memory state (a "s" field); new cards (e.g. fresh miss
                    // cards) have empty data and would panic the backend function.
                    val sql =
                        "select n.tags, case when c.data like '%\"s\"%' then extract_fsrs_retrievability(c.data, " +
                            "case when c.odue != 0 then c.odue else c.due end, c.ivl, " +
                            "${sched.today}, ${sched.dayCutoff}, $now) else null end " +
                            "from cards c join notes n on c.nid = n.id where c.queue != -1"
                    val cards = JSONArray()
                    db.query(sql).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = JSONArray()
                            row.put(cursor.getString(0) ?: "")
                            if (cursor.isNull(1)) row.put(JSONObject.NULL) else row.put(cursor.getDouble(1))
                            cards.put(row)
                        }
                    }
                    // Graded FLASHCARD reviews only: reasoning-item attempts (#7A) also
                    // live in the revlog, but are excluded here so the give-up gate stays
                    // a measure of flashcard study (mirrors desktop _graded_reviews).
                    val reviews =
                        db.queryScalar(
                            "select count() from revlog where ease between 1 and 4 and cid not in " +
                                "(select c.id from cards c join notes n on c.nid = n.id " +
                                "where n.tags like '%vantage::reasoning::%')",
                        )
                    // Merged application-item outcomes: revlog-backed reasoning attempts
                    // (sync-safe) enriched with the confidence/miss-reason/concept kept in
                    // config, plus config entries not represented in the revlog. Mirrors
                    // desktop collect._merged_outcomes so the phone reads the same data.
                    //
                    // Metacognition config is merged across the legacy single key AND every
                    // per-device key ("vantage_perf_outcomes::<device id>"). Distinct
                    // per-device keys never clobber on sync, so two devices practicing
                    // offline both survive the merge (collect._all_perf_config). Keys are
                    // filtered in Kotlin (not via SQL LIKE, whose "_" is a wildcard) and
                    // sorted for a deterministic merge order.
                    val configArr = JSONArray()
                    run {
                        val keys = ArrayList<String>()
                        keys.add(PERF_CONFIG_KEY) // legacy single key first
                        keys.addAll(
                            db
                                .queryStringList("select key from config")
                                .filter { it.startsWith(PERF_DEVICE_PREFIX) }
                                .sorted(),
                        )
                        for (key in keys) {
                            val arr =
                                try {
                                    JSONArray(backend.getConfigJson(key).toStringUtf8())
                                } catch (e: Exception) {
                                    JSONArray()
                                }
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { configArr.put(it) }
                        }
                    }
                    val metaByRid = HashMap<Long, JSONObject>()
                    for (i in 0 until configArr.length()) {
                        val o = configArr.optJSONObject(i) ?: continue
                        if (o.has("revlog_id")) metaByRid[o.optLong("revlog_id")] = o
                    }
                    val merged = JSONArray()
                    val seenRids = HashSet<Long>()
                    db
                        .query(
                            "select r.id, r.ease, r.time, n.tags from revlog r " +
                                "join cards c on r.cid = c.id join notes n on c.nid = n.id " +
                                "where n.tags like '%vantage::reasoning::%' and r.ease between 1 and 4",
                        ).use { cur ->
                            while (cur.moveToNext()) {
                                val rid = cur.getLong(0)
                                val ease = cur.getLong(1)
                                val ms = cur.getLong(2)
                                val tags = cur.getString(3) ?: ""
                                val sec =
                                    tags
                                        .split(" ")
                                        .firstOrNull { it.startsWith("vantage::reasoning::") }
                                        ?.removePrefix("vantage::reasoning::") ?: continue
                                seenRids.add(rid)
                                val meta = metaByRid[rid]
                                // Concept links the item to that concept's recall cards (the
                                // card-level paraphrase test / fluency illusion). Prefer the card
                                // tag, else a config-supplied concept. Mirrors _revlog_outcomes +
                                // _merged_outcomes ("ro.concept or meta.concept").
                                val concept =
                                    tags
                                        .split(" ")
                                        .firstOrNull { it.startsWith("vantage::concept::") }
                                        ?.removePrefix("vantage::concept::")
                                        ?: meta?.optString("concept", "")?.ifEmpty { null }
                                // SIRS skill: the second axis of the miss diagnosis (what kind
                                // of thinking the item tests). Prefer the card tag, else a
                                // config-supplied skill. Mirrors _revlog_outcomes +
                                // _merged_outcomes ("ro.skill or meta.skill").
                                val skill =
                                    tags
                                        .split(" ")
                                        .firstOrNull { it.startsWith("vantage::skill::") }
                                        ?.removePrefix("vantage::skill::")
                                        ?: meta?.optString("skill", "")?.ifEmpty { null }
                                merged.put(
                                    JSONObject()
                                        .put("section", sec)
                                        .put("correct", ease >= 2)
                                        .put("confidence", meta?.optString("confidence", "") ?: "")
                                        .put("reason", meta?.optString("reason", "") ?: "")
                                        .put("concept", concept ?: "")
                                        .put("skill", skill ?: "")
                                        .put("ms", if (meta != null && meta.has("ms")) meta.optLong("ms") else ms),
                                )
                            }
                        }
                    for (i in 0 until configArr.length()) {
                        val o = configArr.optJSONObject(i) ?: continue
                        if (o.has("revlog_id") && seenRids.contains(o.optLong("revlog_id"))) continue
                        merged.put(o)
                    }
                    var pn = 0
                    var pk = 0
                    for (i in 0 until merged.length()) {
                        pn += 1
                        if (merged.getJSONObject(i).optBoolean("correct", false)) pk += 1
                    }
                    // Rebuild the confusability signal for the Rust interleaver from the
                    // merged mistake data + the topics present on the phone's cards.
                    updateConfusability(this, merged, cards)
                    // mcat-scoped due count, IDENTICAL to the daily reminder's
                    // VantageDueReminderReceiver.vantageDueCount() (notes join +
                    // n.tags like '%mcat::%'): review/day-learn due today (queue 2/3)
                    // plus intraday learning (queue 1), for THIS MCAT collection's
                    // tagged cards. So the in-app banner (reviews_due -> dashboard.js)
                    // matches the notification instead of counting the whole collection.
                    val reviewsDue =
                        db.queryScalar(
                            "select count() from cards c join notes n on c.nid = n.id " +
                                "where n.tags like '%mcat::%' and c.queue in (2,3) and c.due <= ${sched.today}",
                        ) +
                            db.queryScalar(
                                "select count() from cards c join notes n on c.nid = n.id " +
                                    "where n.tags like '%mcat::%' and c.queue = 1",
                            )
                    val newRemaining = db.queryScalar("select count() from cards where queue = 0")
                    val examDate = config.get<String>("vantage_exam_date", "") ?: ""
                    val bookSet = config.get<String>("vantage_book_set", "kaplan") ?: "kaplan"
                    // Target readiness composite (3-section scale): drives the score
                    // trajectory's on-pace check. 0 when unset, which the JS treats as
                    // "no target set yet" (abstain). Read as a number (int or float, like
                    // collect.gather's isinstance check); the JS truncates it to an int.
                    val targetScore = config.get<Double>(TARGET_CONFIG_KEY, 0.0) ?: 0.0
                    // Dated readiness snapshots the trajectory fits its slope over
                    // (HISTORY_CONFIG_KEY). Read the raw JSON array so a single malformed
                    // entry can't crash the read; the JS filters bad points defensively.
                    val readinessHistory =
                        try {
                            JSONArray(backend.getConfigJson(HISTORY_CONFIG_KEY).toStringUtf8())
                        } catch (e: Exception) {
                            JSONArray()
                        }
                    // Deck names that have cards. Some decks (e.g. Pankow P/S subdecks)
                    // encode the AAMC topic in the DECK PATH rather than in card tags;
                    // the phone matches these via deck_topic_map so topic coverage counts
                    // them too, mirroring desktop collect.gather's deck_topic loop.
                    val deckNames = JSONArray()
                    db
                        .query("select d.name from decks d where exists (select 1 from cards c where c.did = d.id)")
                        .use { cursor ->
                            while (cursor.moveToNext()) {
                                cursor.getString(0)?.let { deckNames.put(it) }
                            }
                        }
                    // Per-section maturity counts (c.ivl/c.queue) for the study launcher's
                    // read-only Mixed/Blocked status label. The JS turns these into the
                    // decision via the shared sectionShouldMix port, so the label matches
                    // what startSectionStudy will actually do for that section.
                    val sectionMaturity = JSONObject()
                    for (sec in SCIENCE_SECTIONS) {
                        val (mature, review) = sectionMatureReviewCounts(this, sec)
                        sectionMaturity.put(sec, JSONObject().put("mature", mature).put("review", review))
                    }
                    // Feature 1: last successful sync from THIS phone's collection (the
                    // same core `ls` column desktop reads; ms, 0 = never synced). NOT
                    // Prefs.lastSyncTime, which is bumped even on a FAILED sync and is not
                    // touched by the Vantage button's direct syncCollection. queryLongScalar
                    // (Long) is required: an epoch-ms value overflows a 32-bit Int.
                    val lastSyncMs = db.queryLongScalar("select ls from col")
                    // Feature 4: what the student flagged to revisit (native flag column).
                    // Non-suspended flagged cards are counted as flashcards; flagged
                    // reasoning anchors (suspended, queue -1) are excluded from the count
                    // and surfaced as re-servable questions (matches desktop render._flagged).
                    val flaggedCardCount = db.queryScalar("select count() from cards where flags != 0 and queue >= 0")
                    val flaggedReasoning = JSONArray()
                    db
                        .query(
                            "select n.tags, n.flds from cards c join notes n on c.nid = n.id " +
                                "where c.flags != 0 and n.tags like '%vantage::reasoning::%'",
                        ).use { cur ->
                            while (cur.moveToNext()) {
                                val tags = cur.getString(0) ?: ""
                                val section =
                                    tags
                                        .split(" ")
                                        .firstOrNull { it.startsWith("vantage::reasoning::") }
                                        ?.removePrefix("vantage::reasoning::") ?: continue
                                val stem = (cur.getString(1) ?: "").split("\u001f").firstOrNull() ?: continue
                                val qid =
                                    tags
                                        .split(" ")
                                        .firstOrNull { it.startsWith("vantage::rq::") }
                                        ?.removePrefix("vantage::rq::")
                                flaggedReasoning.put(JSONObject().put("section", section).put("stem", stem).put("qid", qid ?: ""))
                            }
                        }
                    JSONObject()
                        .put("cards", cards)
                        .put("deck_names", deckNames)
                        .put("section_maturity", sectionMaturity)
                        .put("n_reviews", reviews)
                        .put("perf", JSONObject().put("n", pn).put("k", pk))
                        .put("perf_outcomes", merged)
                        .put("exam_date", examDate)
                        .put("book_set", bookSet)
                        .put("reviews_due", reviewsDue)
                        .put("new_remaining", newRemaining)
                        .put("last_sync_ms", lastSyncMs)
                        .put("flagged_card_count", flaggedCardCount)
                        .put("flagged_reasoning", flaggedReasoning)
                        .put(
                            "today",
                            java.time.LocalDate
                                .now()
                                .toString(),
                        )
                        // Score-trajectory inputs (mirrors collect.gather): the target
                        // score and the dated readiness snapshots. days-to-exam is derived
                        // in JS from exam_date and today, exactly like collect's days_left.
                        .put("target", targetScore)
                        .put("readiness_history", readinessHistory)
                        .toString()
                }
            // The on-device compute (including the IRT EAP over a fixed 61-point grid)
            // is O(cards + outcomes x grid) -- a few milliseconds even for large decks
            // -- so it runs fine on the WebView main thread. If item counts ever grow
            // enough to jank the first paint, move window.vantageComputeFromRaw into a
            // Web Worker and post the result back before calling __vantageRender.
            view.evaluateJavascript(
                "window.__VANTAGE__ = window.vantageComputeFromRaw($raw); " +
                    "if (window.__vantageRender) window.__vantageRender(); " +
                    // Hand today's folded readiness-snapshot history back to the host to
                    // persist (or the literal null when nothing changed). See below.
                    "(window.__VANTAGE__ && window.__VANTAGE__.readiness_history_persist) || null;",
            ) { result ->
                persistReadinessHistory(result)
            }
        }
    }

    /**
     * Persist today's readiness snapshot that the scoring JS folded into the dated
     * history, so the score trajectory can build a trend across days.
     *
     * `result` is the WebView's JSON serialization of
     * `window.__VANTAGE__.readiness_history_persist`: the full updated
     * `[{ d, point }]` array when today's readiness is a LIVE full 3-section
     * composite that is new or has moved, or the literal string `"null"` when there
     * is nothing new to write. This mirrors collect.gather's once-per-day,
     * full-composite-only write to HISTORY_CONFIG_KEY, so a routine dashboard
     * refresh never dirties the collection (and never bumps the sync usn).
     */
    private fun persistReadinessHistory(result: String?) {
        if (result == null || result == "null") return
        val arr =
            try {
                JSONArray(result)
            } catch (e: Exception) {
                return
            }
        lifecycleScope.launch { withCol { config.set(HISTORY_CONFIG_KEY, arr) } }
    }

    private inner class Bridge(
        val webView: WebView,
    ) {
        @JavascriptInterface
        fun invoke(cmd: String) {
            // Runs on a binder thread; hop to the UI thread for view operations.
            runOnUiThread {
                when {
                    cmd == "vantage:back" -> finish()
                    // After a flashcard recap, "Back to dashboard" (reviewer.js _close ->
                    // vpy('studydone')) asks the host to recompute so the just-studied
                    // cards show in the scores/counts. Reloading the asset re-runs
                    // injectLiveScores in onPageFinished. Mirrors desktop's studydone.
                    cmd == "vantage:studydone" -> webView.reload()
                    // Plain refresh, and refresh:<tab> (the Refresh button, which keeps
                    // the student on their current tab). The reload cannot re-bake the
                    // page, so the tab rides across it in sessionStorage, set by the web
                    // layer before this fires and restored once on load; here we only
                    // trigger the same asset reload for both.
                    cmd == "vantage:refresh" || cmd.startsWith("vantage:refresh:") -> webView.reload()
                    cmd == "vantage:sync:trigger" -> triggerSync(webView)
                    // Interleaved / whole-deck study: Anki's own reviewer + queue.
                    cmd == "vantage:study" || cmd == "vantage:study:interleave" -> {
                        // Keep the whole-queue review Mixed regardless of any per-section
                        // Blocked a prior section study left in the shared engine mode, so
                        // this CTA behaves exactly as before (a mixed review). The engine
                        // only reorders review-stage cards; new/learning stay untouched.
                        lifecycleScope.launch {
                            withCol { setInterleaveMode(this, "Mixed") }
                            launchFlashcardReviewer()
                        }
                    }
                    // Feature 3: vantage:studytimed:<minutes>:<section-or-interleave>.
                    // Matched BEFORE the generic vantage:study: prefix (more specific first).
                    cmd.startsWith("vantage:studytimed:") -> {
                        val rest = cmd.removePrefix("vantage:studytimed:")
                        val minutes = rest.substringBefore(':').toIntOrNull()?.coerceIn(0, 180) ?: 0
                        val section = rest.substringAfter(':', "")
                        lifecycleScope.launch {
                            applyVantageTimebox(minutes) // set timeLim (saving the prior value)
                            if (section.isEmpty() || section == "interleave") {
                                withCol { setInterleaveMode(this, "Mixed") }
                                launchFlashcardReviewer()
                            } else {
                                startSectionStudy(section) // reused unchanged; launches the native reviewer
                            }
                        }
                    }
                    // Feature 4: study the flagged flashcard pool via a "Vantage Flagged"
                    // filtered deck. Exact match BEFORE the generic vantage:study: prefix.
                    cmd == "vantage:study:flagged" -> startFlaggedStudy()
                    // Feature 4: flag/unflag a reasoning question (creates/reuses its anchor).
                    cmd.startsWith("vantage:flagq:") -> flagReasoning(cmd)
                    // Per-section flashcards: build a filtered deck for the section's
                    // tag, then study it through the same real reviewer.
                    cmd.startsWith("vantage:study:") -> startSectionStudy(cmd.removePrefix("vantage:study:"))
                    cmd.startsWith("vantage:practice2:") -> recordPractice2(cmd)
                    cmd.startsWith("vantage:practice:") -> recordPractice(cmd)
                    // Inline "Generate cards" tap. Card generation is a computer-only
                    // feature, so on the phone this is an honest explainer, never a
                    // silent no-op (see showGenerateCardsInfo).
                    cmd.startsWith("vantage:gencards:") -> showGenerateCardsInfo()
                    cmd.startsWith("vantage:examdatesave:") -> {
                        // Persist-only (no reload): fires on every value change so the exam
                        // date is committed to config even if the field never blurs before an
                        // external Sync (the examdate:/reload path fires on blur/Enter).
                        val iso = cmd.removePrefix("vantage:examdatesave:").trim()
                        lifecycleScope.launch {
                            withCol {
                                if (iso.isEmpty()) config.remove("vantage_exam_date") else config.set("vantage_exam_date", iso)
                            }
                        }
                    }
                    cmd.startsWith("vantage:examdate:") -> {
                        val iso = cmd.removePrefix("vantage:examdate:").trim()
                        lifecycleScope.launch {
                            withCol {
                                if (iso.isEmpty()) config.remove("vantage_exam_date") else config.set("vantage_exam_date", iso)
                            }
                            webView.reload()
                        }
                    }
                    cmd.startsWith("vantage:targetsave:") -> {
                        // Persist-only (no reload): fires on every keystroke so the target
                        // is committed to config even if the field never blurs before an
                        // external Sync (the target:/reload path only fires on blur/Enter).
                        val v = cmd.removePrefix("vantage:targetsave:").trim()
                        lifecycleScope.launch {
                            withCol {
                                if (v.isEmpty()) {
                                    config.remove("vantage_target_score")
                                } else {
                                    v.toDoubleOrNull()?.let { config.set("vantage_target_score", it.toInt()) }
                                }
                            }
                        }
                    }
                    cmd.startsWith("vantage:target:") -> {
                        val v = cmd.removePrefix("vantage:target:").trim()
                        lifecycleScope.launch {
                            withCol {
                                if (v.isEmpty()) {
                                    config.remove("vantage_target_score")
                                } else {
                                    v.toDoubleOrNull()?.let { config.set("vantage_target_score", it.toInt()) }
                                }
                            }
                            webView.reload()
                        }
                    }
                    cmd.startsWith("vantage:bookset:") -> {
                        val v = cmd.removePrefix("vantage:bookset:").trim()
                        // No reload: the page swaps the named book client-side.
                        if (v.isNotEmpty()) lifecycleScope.launch { withCol { config.set("vantage_book_set", v) } }
                    }
                    cmd.startsWith("vantage:open:") ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cmd.removePrefix("vantage:open:"))))
                        } catch (e: Exception) {
                            // no browser available; ignore
                        }
                }
            }
        }
    }

    /**
     * Honest response to tapping an inline "Generate cards" button on the phone.
     *
     * Card generation is a computer-only feature: the source-traced AI pipeline
     * (vantage_tools/ai, run by the desktop add-on via cardgen_bridge) does not
     * ship on the phone, and the web page never sets __VANTAGE_CARDGEN__ here, so
     * there is nothing to run on-device. Rather than silently do nothing (which
     * would look broken), tell the student the truth: generation happens on the
     * computer, each card is checked against its source before it is added, and
     * the results land in a real deck named "Vantage Generated"
     * (cardgen_bridge.GEN_DECK) that syncs back to the phone. Honesty first: no
     * fabricated success, no fake cards.
     */
    private fun showGenerateCardsInfo() {
        showThemedToast(
            this,
            "Card generation runs on the computer. New cards are checked against " +
                "their source, then added to your Vantage Generated deck and synced here.",
            false,
        )
    }

    /**
     * Additive Sync button: run AnkiDroid's real collection sync (the same backend
     * call the manual and auto sync use), then reload the dashboard so the scores
     * reflect anything pulled. On top of auto-sync on open/close, not a replacement.
     * On success the reload resets the button; on failure we tell the webview so it
     * shows a plain-language message.
     */
    private fun triggerSync(web: WebView) {
        val auth = syncAuth()
        if (auth == null) {
            web.evaluateJavascript("window.vantageSyncDone && window.vantageSyncDone(false)", null)
            return
        }
        lifecycleScope.launch {
            try {
                withProgress("Syncing") {
                    withCol { syncCollection(auth, syncMedia = false) }
                }
                web.reload()
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Vantage manual sync failed")
                web.evaluateJavascript("window.vantageSyncDone && window.vantageSyncDone(false)", null)
            }
        }
    }

    /**
     * Study one section's flashcards on the shared engine: gather the section's
     * tagged cards into a filtered deck (via the backend, real FSRS scheduling),
     * select it, then hand off to Anki's own reviewer.
     */
    private fun startSectionStudy(section: String) {
        lifecycleScope.launch {
            withCol {
                // Decide Mixed vs Blocked automatically from this section's maturity
                // (same c.ivl/c.queue counts + thresholds as the desktop reviewer), then
                // point the Rust interleaver's mode at that choice for this study. The
                // engine only ever reorders review-stage cards, so new / still-learning
                // cards stay grouped no matter which mode -- exactly the desktop behavior.
                // `section` is a bare section key now ("mix:" is gone everywhere), so the
                // tag search below can never become the old empty "tag:mcat::mix:...::*".
                val (mature, review) = sectionMatureReviewCounts(this, section)
                setInterleaveMode(this, if (sectionShouldMix(mature, review)) "Mixed" else "Blocked")
                val name = "Vantage $section"
                val existing = decks.idForName(name) ?: 0L
                val deckData =
                    filteredDeckForUpdate {
                        id = existing
                        this.name = name
                        allowEmpty = true
                        config =
                            filtered {
                                reschedule = true
                                searchTerms.add(
                                    searchTerm {
                                        // Due only (new + due), matching the desktop
                                        // "Flashcards" flow. Grading reschedules normally.
                                        search = "tag:mcat::$section::* -is:suspended (is:new OR is:due)"
                                        limit = 200
                                        order =
                                            Deck.Filtered.SearchTerm.Order
                                                .forNumber(0)
                                    },
                                )
                            }
                    }
                val did = sched.addOrUpdateFilteredDeck(deckData).id
                sched.rebuildFilteredDeck(did)
                decks.select(did)
            }
            launchFlashcardReviewer()
        }
    }

    /**
     * Feature 3: apply an N-minute timebox for the next native flashcard session
     * (0 = clear it). Reuses Anki's built-in timebox: the native Reviewer already
     * checks timeboxReached() after each answered card and shows Continue / Finish,
     * so the current card always finishes first. No reviewer/scheduler edits. The
     * student's own timeLim is saved once so restoreVantageTimebox() can put it back.
     */
    private suspend fun applyVantageTimebox(minutes: Int) {
        withCol {
            val prev = (config.get<Double>("timeLim", 0.0) ?: 0.0).toInt()
            // Remember the user's own value once (-1 sentinel = not yet saved).
            val saved = (config.get<Double>(VANTAGE_PREV_TIMELIM, -1.0) ?: -1.0).toInt()
            if (saved < 0) config.set(VANTAGE_PREV_TIMELIM, prev)
            config.set("timeLim", if (minutes > 0) minutes * 60 else prev)
        }
    }

    /**
     * Feature 3: restore the student's own timebox after a Vantage-capped session.
     * Called from onCreate so returning to the dashboard cleans up, whatever route
     * was taken. No-op when nothing was saved.
     */
    private fun restoreVantageTimebox() {
        lifecycleScope.launch {
            withCol {
                val saved = (config.get<Double>(VANTAGE_PREV_TIMELIM, -1.0) ?: -1.0).toInt()
                if (saved < 0) return@withCol
                config.set("timeLim", saved)
                config.remove(VANTAGE_PREV_TIMELIM)
            }
        }
    }

    /**
     * Launch AnkiDroid's native flashcard reviewer for the queue/deck already
     * selected, recording the session start so [onFlashcardSessionEnded] can count
     * the cards actually graded during it. Unlike the old fire-and-finish() launch,
     * the Vantage activity stays alive so a real recap can show on return (parity
     * with the desktop reviewer's done screen). Call on the main thread.
     */
    private fun launchFlashcardReviewer() {
        flashcardSessionStartMs = TimeManager.time.intTimeMS()
        reviewerLauncher.launch(Reviewer.getIntent(this))
    }

    /**
     * The native reviewer returned. Restore any Vantage timebox (the session is
     * over), then count the cards actually graded during the session from the revlog
     * -- real answers only (ease 1..4, so manual reschedules are excluded), stamped
     * at/after the session start -- and queue the recap. Honesty first: the number is
     * a real query over THIS session's window, never an estimate.
     */
    private fun onFlashcardSessionEnded() {
        restoreVantageTimebox()
        val start = flashcardSessionStartMs
        flashcardSessionStartMs = 0L
        if (start <= 0L) return
        lifecycleScope.launch {
            val reviewed =
                withCol {
                    db.queryScalar("select count() from revlog where id >= $start and ease between 1 and 4")
                }
            pendingRecapReviewed = reviewed
            maybeShowRecap()
        }
    }

    /**
     * Show the flashcard recap once BOTH the page is ready and a real session count
     * is pending. Reuses the shared reviewer recap overlay (reviewer.js `vreview`),
     * so the phone matches the desktop reviewer's done screen. When nothing was
     * graded there is nothing honest to celebrate, so the recap is skipped entirely
     * (no fabricated "nice work", no needless reload). Idempotent, so it is safe to
     * call from both the reviewer-return callback and onPageFinished.
     */
    private fun maybeShowRecap() {
        if (!pageReady) return
        val reviewed = pendingRecapReviewed
        if (reviewed < 0) return
        pendingRecapReviewed = -1
        if (reviewed <= 0) return
        webView.evaluateJavascript(
            "window.vreview && window.vreview.recap && window.vreview.recap({ answered: $reviewed });",
            null,
        )
    }

    /**
     * Feature 4: flag/unflag a reasoning question. It has no queue card until
     * answered, so create (or reuse) its suspended anchor (mirroring recordPractice2),
     * then set the native flag on it. Any non-zero flag reads back as "flagged".
     */
    private fun flagReasoning(cmd: String) {
        val json =
            try {
                JSONObject(java.net.URLDecoder.decode(cmd.removePrefix("vantage:flagq:"), "UTF-8"))
            } catch (e: Exception) {
                return
            }
        val stem = json.optString("stem", "")
        if (stem.isEmpty()) return
        val on = json.optBoolean("on", true)
        lifecycleScope.launch {
            withCol {
                val qidTag = "vantage::rq::${md5hex(stem)}"
                var cid =
                    db
                        .queryLongList(
                            "select c.id from cards c join notes n on c.nid = n.id where n.tags like ?",
                            "%$qidTag%",
                        ).firstOrNull()
                if (cid == null && on) {
                    val basic = notetypes.byName("Basic") ?: return@withCol
                    val note = newNote(basic)
                    note.setItem("Front", stem)
                    val answer = json.optString("answer", "")
                    val explain = json.optString("explain", "")
                    note.setItem("Back", if (answer.isNotEmpty()) "$answer<br><br>$explain" else explain)
                    note.addTag("vantage::reasoning::${json.optString("section", "")}")
                    note.addTag(qidTag)
                    json.optString("concept", "").takeIf { it.isNotEmpty() }?.let { note.addTag("vantage::concept::$it") }
                    json.optString("skill", "").takeIf { it.isNotEmpty() }?.let { note.addTag("vantage::skill::$it") }
                    addNote(note, decks.id("Vantage Reasoning"))
                    val cids = cardIdsOfNote(note.id)
                    if (cids.isNotEmpty()) {
                        sched.suspendCards(cids)
                        cid = cids[0]
                    }
                }
                cid?.let { setUserFlagForCards(listOf(it), if (on) VANTAGE_FLAG else 0) }
            }
        }
    }

    /**
     * Feature 4: study the flagged flashcard pool via a "Vantage Flagged" filtered
     * deck ("-flag:0 -is:suspended"), then hand off to Anki's own reviewer. Grading
     * reschedules normally; suspended reasoning anchors are excluded by -is:suspended.
     */
    private fun startFlaggedStudy() {
        lifecycleScope.launch {
            withCol {
                val name = "Vantage Flagged"
                val existing = decks.idForName(name) ?: 0L
                val deckData =
                    filteredDeckForUpdate {
                        id = existing
                        this.name = name
                        allowEmpty = true
                        config =
                            filtered {
                                reschedule = true
                                searchTerms.add(
                                    searchTerm {
                                        search = "-flag:0 -is:suspended"
                                        limit = 200
                                        order =
                                            Deck.Filtered.SearchTerm.Order
                                                .forNumber(0)
                                    },
                                )
                            }
                    }
                val did = sched.addOrUpdateFilteredDeck(deckData).id
                sched.rebuildFilteredDeck(did)
                decks.select(did)
            }
            launchFlashcardReviewer()
        }
    }

    /**
     * Fold a practice session into the performance-outcomes store on THIS phone's
     * collection, so the Performance score reflects real application-item practice
     * (mirrors the desktop add-on).
     */
    private fun recordPractice(cmd: String) {
        // cmd: vantage:practice:<section>:<correct>:<total>
        val parts = cmd.split(":")
        if (parts.size != 5) return
        val section = parts[2]
        val correct = parts[3].toIntOrNull() ?: return
        val total = parts[4].toIntOrNull() ?: return
        lifecycleScope.launch {
            withCol {
                // This device's OWN per-device key, so two devices practicing offline
                // never clobber each other on sync (mirrors collect.record_metacognition).
                val key = perfDeviceKey(this)
                val arr =
                    try {
                        JSONArray(backend.getConfigJson(key).toStringUtf8())
                    } catch (e: Exception) {
                        JSONArray()
                    }
                for (i in 0 until total) {
                    arr.put(JSONObject().put("section", section).put("correct", i < correct))
                }
                config.set(key, arr)
            }
        }
    }

    /**
     * Rich practice session (per-item confidence + miss reason). Records each
     * outcome for the metacognition and "how you lose points" panels, and turns
     * each miss into a spaced re-review card (deck "Vantage Misses", FSRS).
     */
    private fun recordPractice2(cmd: String) {
        val decoded =
            try {
                java.net.URLDecoder.decode(cmd.removePrefix("vantage:practice2:"), "UTF-8")
            } catch (e: Exception) {
                return
            }
        val json =
            try {
                JSONObject(decoded)
            } catch (e: Exception) {
                return
            }
        val section = json.optString("section", "")
        // "practice" or "test": tags each answer so Test-mode results are
        // distinguishable in reporting, while feeding the SAME performance pipeline.
        val mode = json.optString("mode", "")
        val items = json.optJSONArray("items") ?: return
        lifecycleScope.launch {
            withCol {
                // This device's OWN per-device key (see recordPractice); the correct/
                // incorrect outcome also lands in the revlog below and is linked by
                // revlog_id, so the read-side merge counts each answer once.
                val key = perfDeviceKey(this)
                val arr =
                    try {
                        JSONArray(backend.getConfigJson(key).toStringUtf8())
                    } catch (e: Exception) {
                        JSONArray()
                    }
                val seen = (config.get<List<String>>("vantage_miss_seen", emptyList()) ?: emptyList()).toMutableList()
                val basic = notetypes.byName("Basic")
                val missDeck by lazy { decks.id("Vantage Misses") }
                val reasoningDeck by lazy { decks.id("Vantage Reasoning") }
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val correct = it.optBoolean("correct", false)
                    val stem = it.optString("stem", "")
                    val answer = it.optString("answer", "")
                    val explain = it.optString("explain", "")
                    val ms = it.optLong("ms", 0L)
                    // The AAMC concept this item tests (if the practice set provides one):
                    // it links the reasoning card to that concept's recall cards for the
                    // card-level paraphrase test (fluency illusion). Mirrors desktop.
                    val concept = it.optString("concept", "")
                    // The AAMC SIRS skill this item tests (author-tagged in the bank): the
                    // second axis of the miss diagnosis. Tagged on the anchor card like
                    // concept, so it syncs for free. Mirrors desktop log_reasoning_outcome.
                    val skill = it.optString("skill", "")
                    // #7A: record the outcome as a real revlog row on a suspended anchor
                    // card, so correct/incorrect syncs + merges across devices (no config
                    // clobber). Mirrors desktop collect.log_reasoning_outcome.
                    var rid: Long? = null
                    if (stem.isNotEmpty() && basic != null) {
                        val qidTag = "vantage::rq::${md5hex(stem)}"
                        var cid =
                            db
                                .queryLongList(
                                    "select c.id from cards c join notes n on c.nid = n.id where n.tags like ?",
                                    "%$qidTag%",
                                ).firstOrNull()
                        if (cid == null) {
                            val note = newNote(basic)
                            note.setItem("Front", stem)
                            note.setItem("Back", if (answer.isNotEmpty()) "$answer<br><br>$explain" else explain)
                            note.addTag("vantage::reasoning::$section")
                            note.addTag(qidTag)
                            // concept tag links this item to the concept's recall cards
                            if (concept.isNotEmpty()) note.addTag("vantage::concept::$concept")
                            // skill tag feeds the SIRS-skill axis of the miss diagnosis
                            if (skill.isNotEmpty()) note.addTag("vantage::skill::$skill")
                            addNote(note, reasoningDeck)
                            val cids = cardIdsOfNote(note.id)
                            if (cids.isNotEmpty()) {
                                // anchors are never studied through the queue; the card exists
                                // only so its attempts live in the (synced) revlog.
                                sched.suspendCards(cids)
                                cid = cids[0]
                            }
                        }
                        if (cid != null) {
                            var newRid = TimeManager.time.intTimeMS()
                            while (db.queryScalar("select count() from revlog where id = ?", newRid) > 0) newRid++
                            db.execute(
                                "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) " +
                                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                newRid,
                                cid,
                                -1,
                                if (correct) 3 else 1,
                                0,
                                0,
                                0,
                                ms.coerceIn(0L, 600000L),
                                1,
                            )
                            rid = newRid
                        }
                    }
                    val entry =
                        JSONObject()
                            .put("section", section)
                            .put("correct", correct)
                            .put("confidence", it.optString("confidence", ""))
                            .put("reason", it.optString("reason", ""))
                            .put("concept", concept)
                            .put("skill", skill)
                            .put("ms", ms)
                            .put("mode", mode)
                    if (rid != null) {
                        // link metacognition to its revlog outcome so the merge on read
                        // counts this answer once, not twice.
                        entry.put("revlog_id", rid)
                        entry.put("logged", true)
                    }
                    arr.put(entry)
                    // spaced re-review card for a miss (deck "Vantage Misses", FSRS)
                    if (!correct && stem.isNotEmpty() && basic != null) {
                        val md5 = md5hex(stem)
                        if (!seen.contains(md5)) {
                            val note = newNote(basic)
                            note.setItem("Front", stem)
                            note.setItem("Back", if (answer.isNotEmpty()) "$answer<br><br>$explain" else explain)
                            note.addTag("vantage::miss::$section")
                            addNote(note, missDeck)
                            seen.add(md5)
                        }
                    }
                }
                config.set(key, arr)
                config.set("vantage_miss_seen", seen)
            }
        }
    }

    /** Stable 12-hex-char id from a question's text (matches desktop _reasoning_qid). */
    private fun md5hex(s: String): String =
        java.security.MessageDigest
            .getInstance("MD5")
            .digest(s.toByteArray())
            .joinToString("") { b -> "%02x".format(b) }
            .substring(0, 12)

    /**
     * This device's OWN metacognition config key, "vantage_perf_outcomes::<device
     * id>". Each device writes only its own key, so two devices practicing offline
     * never clobber each other on sync; the read path unions every per-device key
     * plus the legacy single key. Mirrors desktop collect._perf_device_key.
     */
    private fun perfDeviceKey(col: Collection): String = PERF_DEVICE_PREFIX + deviceId(col)

    /**
     * A stable, LOCAL (never synced) id for this device, so its per-device key
     * differs from any other device's. Stored in a sibling file next to the
     * collection (app-private storage, which sync never touches), mirroring desktop
     * collect._device_id. Falls back to a once-seeded config key (which does sync,
     * so best-effort only) when the sibling file can't be used.
     */
    private fun deviceId(col: Collection): String {
        val path = col.db.path
        if (path.isNotEmpty() && path != ":memory:") {
            val idFile = File(path + DEVICE_ID_SUFFIX)
            try {
                if (idFile.exists()) {
                    val existing = idFile.readText(Charsets.UTF_8).trim()
                    if (existing.isNotEmpty()) return existing
                }
                val did = UUID.randomUUID().toString().replace("-", "")
                idFile.writeText(did, Charsets.UTF_8)
                return did
            } catch (e: Exception) {
                // no writable sibling file; fall through to the config fallback
            }
        }
        var did = col.config.get<String>(DEVICE_ID_CONFIG_KEY, "") ?: ""
        if (did.isEmpty()) {
            did = UUID.randomUUID().toString().replace("-", "")
            col.config.set(DEVICE_ID_CONFIG_KEY, did)
        }
        return did
    }

    /**
     * Rebuild the optional confusability signal the Rust interleaver reads from the
     * `vantage.interleave` config, from the student's mistake data + the topics on
     * their cards. Same-section topics are the confusable (similar) ones, and
     * interleaving helps most for confusable categories (Brunmair & Richter 2019),
     * so every pair of distinct topics that share a science section is weighted by
     * how many application mistakes the student has made in that section. The engine
     * only reorders the round-robin rotation (never FSRS); an empty/ineffective map
     * falls back bit-for-bit to the naive order. The user's existing mode / prefix /
     * seed are preserved (never clobbered), and we only write when a real, changed
     * signal exists, to avoid needless sync churn.
     *
     * `cards` is the [tags, retrievability] rows already gathered for scoring;
     * `outcomes` is the merged application-item outcome list.
     */
    private fun updateConfusability(
        col: Collection,
        outcomes: JSONArray,
        cards: JSONArray,
    ) {
        // Misses per science section (application outcomes graded incorrect).
        val missBySection = HashMap<String, Int>()
        for (i in 0 until outcomes.length()) {
            val o = outcomes.optJSONObject(i) ?: continue
            if (o.optBoolean("correct", false)) continue
            val s = o.optString("section", "")
            if (s.isEmpty()) continue
            missBySection[s] = (missBySection[s] ?: 0) + 1
        }
        if (missBySection.isEmpty()) return // no mistake signal yet -> leave config untouched

        // Preserve any existing settings; default to Mixed + "mcat" so a fresh phone
        // actually uses the signal, while respecting an explicit user choice.
        val existing = col.config.getObject(INTERLEAVE_CONFIG_KEY, JSONObject())
        val mode = existing.optString("mode", "Mixed").ifEmpty { "Mixed" }
        val prefix = existing.optString("topic_tag_prefix", "mcat").ifEmpty { "mcat" }
        val seed = existing.optLong("seed", 0L)

        // Distinct topics present on the phone's cards, grouped by section. The topic
        // key is the FIRST tag under "<prefix>::" (exactly how the interleaver buckets
        // a card), and its section is that tag's second "::" segment, so the keys we
        // emit are the real bucket keys the engine will match.
        val tagPrefix = "$prefix::"
        val topicsBySection = HashMap<String, java.util.TreeSet<String>>()
        for (i in 0 until cards.length()) {
            val row = cards.optJSONArray(i) ?: continue
            val tags = row.optString(0, "")
            val topic = tags.split(" ").firstOrNull { it.startsWith(tagPrefix) } ?: continue
            val parts = topic.split("::")
            if (parts.size < 2 || parts[1].isEmpty()) continue
            topicsBySection.getOrPut(parts[1]) { java.util.TreeSet() }.add(topic)
        }

        // One undirected pair per same-section topic pair, in a section with misses,
        // weighted by that section's miss count. Bounded and deterministic (sorted
        // sections + topics, i < j) so the persisted config is stable across refreshes.
        val pairs = JSONArray()
        var emitted = 0
        outer@ for ((section, missCount) in missBySection.toSortedMap()) {
            if (missCount <= 0) continue
            val all = topicsBySection[section]?.toList() ?: continue
            val topics = if (all.size > MAX_TOPICS_PER_SECTION) all.subList(0, MAX_TOPICS_PER_SECTION) else all
            for (a in topics.indices) {
                for (b in a + 1 until topics.size) {
                    if (emitted >= MAX_CONFUSABILITY_PAIRS) break@outer
                    pairs.put(
                        JSONObject()
                            .put("topic_a", topics[a])
                            .put("topic_b", topics[b])
                            .put("weight", missCount.toDouble()),
                    )
                    emitted += 1
                }
            }
        }
        if (pairs.length() == 0) return // topics not tagged under the prefix -> nothing to bias

        // Only write when the pair list actually changed (mode/prefix/seed are carried
        // over unchanged), so a routine dashboard refresh doesn't bump the sync usn.
        val prev = existing.optJSONArray("confusability")?.toString() ?: "[]"
        if (prev == pairs.toString()) return
        col.config.set(
            INTERLEAVE_CONFIG_KEY,
            JSONObject()
                .put("mode", mode)
                .put("topic_tag_prefix", prefix)
                .put("seed", seed)
                .put("confusability", pairs),
        )
    }

    /**
     * (mature, review) counts for a section's review-stage cards -- the input to the
     * automatic Mixed/Blocked decision. review = its non-suspended review-stage cards
     * (queue 2); mature = those matured (ivl >= the mature line). Measured over the
     * whole review pool (not just today's due), mirroring the desktop
     * _section_maturity_counts so both platforms decide identically.
     */
    private fun sectionMatureReviewCounts(
        col: Collection,
        section: String,
    ): Pair<Int, Int> {
        val review =
            col.db.queryScalar(
                "select count() from cards c join notes n on c.nid = n.id " +
                    "where n.tags like '%mcat::$section::%' and c.queue = 2",
            )
        val mature =
            col.db.queryScalar(
                "select count() from cards c join notes n on c.nid = n.id " +
                    "where n.tags like '%mcat::$section::%' and c.queue = 2 and c.ivl >= $INTERLEAVE_MATURE_IVL_DAYS",
            )
        return Pair(mature, review)
    }

    /**
     * Port of scoring.section_should_mix (and mobile_scoring.sectionShouldMix): a
     * section studies Mixed iff it has enough review-stage cards AND a high enough
     * share of them have matured; otherwise Blocked. Honest default is Blocked. The
     * three thresholds MUST match ScoringConfig.interleave_* and the JS CFG.
     */
    private fun sectionShouldMix(
        matureCount: Int,
        reviewCount: Int,
    ): Boolean {
        if (reviewCount < INTERLEAVE_MIN_REVIEW_CARDS) return false
        if (matureCount < INTERLEAVE_MATURE_FRACTION * reviewCount) return false
        return true
    }

    /**
     * Set just the Rust interleaver's `mode` ("Mixed" / "Blocked" / "Off") in the
     * `vantage.interleave` config, preserving topic_tag_prefix / seed / confusability
     * / priorities. No-op when unchanged, so it never dirties the sync usn needlessly.
     */
    private fun setInterleaveMode(
        col: Collection,
        mode: String,
    ) {
        val existing = col.config.getObject(INTERLEAVE_CONFIG_KEY, JSONObject())
        if (existing.optString("mode", "") == mode) return
        val next =
            JSONObject()
                .put("mode", mode)
                .put("topic_tag_prefix", existing.optString("topic_tag_prefix", "mcat").ifEmpty { "mcat" })
                .put("seed", existing.optLong("seed", 0L))
        existing.optJSONArray("confusability")?.let { next.put("confusability", it) }
        existing.optJSONArray("priorities")?.let { next.put("priorities", it) }
        col.config.set(INTERLEAVE_CONFIG_KEY, next)
    }

    companion object {
        // Metacognition stores (mirrors collect.PERF_CONFIG_KEY / PERF_DEVICE_PREFIX).
        private const val PERF_CONFIG_KEY = "vantage_perf_outcomes"
        private const val PERF_DEVICE_PREFIX = "vantage_perf_outcomes::"

        // Local (never synced) per-device id (mirrors collect.DEVICE_ID_*).
        private const val DEVICE_ID_SUFFIX = ".vantage_device_id"
        private const val DEVICE_ID_CONFIG_KEY = "vantage_device_id"

        // Score-trajectory inputs (mirrors collect.TARGET_CONFIG_KEY / HISTORY_CONFIG_KEY):
        // the target readiness composite and the dated readiness snapshots.
        private const val TARGET_CONFIG_KEY = "vantage_target_score"
        private const val HISTORY_CONFIG_KEY = "vantage_readiness_history"

        // Interleave config the Rust queue builder reads (rslib interleave.rs).
        private const val INTERLEAVE_CONFIG_KEY = "vantage.interleave"

        // Automatic maturity-gated interleaving. These MUST stay identical to
        // ScoringConfig.interleave_* (pylib + vantage_core) and the mobile_scoring.js
        // CFG, so the desktop reviewer, the mobile dashboard label, and this bridge all
        // reach the same Mixed/Blocked decision from the same c.ivl/c.queue counts.
        private const val INTERLEAVE_MATURE_IVL_DAYS = 21
        private const val INTERLEAVE_MATURE_FRACTION = 0.60
        private const val INTERLEAVE_MIN_REVIEW_CARDS = 12

        // The three science sections that carry flashcards (mirrors scoring.SECTIONS).
        private val SCIENCE_SECTIONS = arrayOf("chem_phys", "bio_biochem", "psych_soc")

        // Bounds so the confusability blob stays small regardless of deck size.
        private const val MAX_TOPICS_PER_SECTION = 24
        private const val MAX_CONFUSABILITY_PAIRS = 400

        // Feature 4: the native flag written when a question/card is flagged (1 = red,
        // == Flag.RED.code). Reads treat ANY non-zero flag as flagged.
        private const val VANTAGE_FLAG = 1

        // Feature 3: config key holding the student's own timebox (timeLim, seconds)
        // while a Vantage session cap is active, so it can be restored afterward.
        private const val VANTAGE_PREV_TIMELIM = "vantage_prev_timelim"

        // Saved-instance key for an in-flight flashcard session's start (epoch ms), so
        // the recap survives this activity being reclaimed while the reviewer is up.
        private const val KEY_FLASHCARD_SESSION_START = "vantage_flashcard_session_start"

        fun getIntent(context: Context): Intent = Intent(context, VantageDashboardActivity::class.java)
    }
}
